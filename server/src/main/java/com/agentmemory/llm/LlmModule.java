package com.agentmemory.llm;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.ProviderAuth;
import com.agentmemory.llmrecall.LlmRecallProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the LLM provider layer into the Spring context (ARCHITECTURE.md §2.4, §6).
 *
 * <p>Beans are constructed from the resolved {@link AgentMemoryConfig} (invariant #1, single config
 * load) and injected, never read from a static singleton (invariant #12). The provider matrix is
 * data-driven via {@link ProviderFactory}; the required chat {@link LlmProvider} is always built,
 * while the {@link Embedder} is only built when the embeddings axis is configured (DD-005 — it is a
 * separate, optional axis).
 *
 * <p>The {@link LlmHealthGate} bean runs its probes <em>during bean creation</em>: a failure of the
 * required chat provider throws here and aborts context startup (fail-fast, invariant #13), exactly
 * as a bad data dir does in {@code ConfigModule}. The embeddings probe is non-fatal.
 */
@Configuration(proxyBeanMethods = false)
public class LlmModule {

    private static final Logger log = LoggerFactory.getLogger(LlmModule.class);

    /** The data-driven provider registry. New providers (issue #40) register here. */
    @Bean
    public ProviderFactory providerFactory() {
        return new ProviderFactory();
    }

    /**
     * The required chat/consolidation provider, selected by {@code agent-memory.llm.auth.provider}.
     * Always present (DD-005); a missing/unknown provider key fails fast at bean creation.
     *
     * <p>Marked {@link Primary}: since issue #146 added the optional {@link #recallLlmProvider}, a bare
     * by-type {@link LlmProvider} injection has two candidates. The chat/consolidation provider is the
     * canonical one — every heavyweight consumer (consolidation, chat, handoff, lint, bootstrap) wants
     * it — so it wins by-type, and the recall steps opt in to the lighter provider explicitly via
     * {@code @Qualifier("recallLlmProvider")}. This also keeps the single required provider the unique
     * candidate for any {@code @ConditionalOnSingleCandidate(LlmProvider.class)}. (Embedder injections
     * are unaffected: they all bind by {@code @Qualifier("embedder")}, which beats {@code @Primary}.)
     */
    @Bean
    @Primary
    public LlmProvider llmProvider(ProviderFactory factory, AgentMemoryConfig config) {
        ProviderAuth auth = resolveLlmAuth(config);
        LlmProvider provider = factory.createLlmProvider(auth);
        log.info("LLM provider configured: id={}, model={}", provider.id(), provider.model());
        return provider;
    }

    /**
     * Resolve the chat-axis auth, defaulting the {@code openai-oauth} token-file path to
     * {@code <data-dir>/auth.json} when it is left unset (issue #113). The data dir is known only here
     * in the wiring layer, so the default is applied before the (data-dir-agnostic) factory builds the
     * provider — keeping the factory seam a pure {@code ProviderAuth -> provider} mapping.
     */
    private static ProviderAuth resolveLlmAuth(AgentMemoryConfig config) {
        ProviderAuth auth = config.llm().auth();
        if (!OpenAiOAuthLlmProvider.PROVIDER_KEY.equals(auth.providerKey()) || auth.oauth().hasTokenFile()) {
            return auth;
        }
        String defaultTokenFile =
                config.dataDir().resolve(OpenAiOAuthLlmProvider.DEFAULT_TOKEN_FILE).toString();
        return new ProviderAuth(auth.provider(), auth.apiKey(), auth.baseUrl(), auth.model(),
                new ProviderAuth.OAuth(defaultTokenFile));
    }

    /**
     * The provider the LLM-assisted recall steps use (issue #146, Fase 5): the {@code QueryExpander},
     * the {@code CandidateReranker} (the rerank fallback) and the {@code BriefSynthesizer}. It lets a
     * deployment route the cheap, high-frequency recall calls to a faster/cheaper model than the heavy
     * chat/consolidation provider, configured under {@code agent-memory.recall.llm.auth} (the shape of
     * {@code llm.auth}). <strong>Default = the primary chat provider</strong>, so leaving recall auth
     * unset is a zero-change no-op.
     *
     * <p>The {@link LlmRecallProperties} carrier is injected via {@link ObjectProvider} because it is
     * registered by the (auto-configured) {@code LlmRecallConfiguration}, which need not be present when
     * this module is loaded in isolation (e.g. the LLM module's own fail-fast test); when it is absent
     * the recall axis is simply unconfigured and recall reuses the primary.
     *
     * <p><strong>Best-effort, never fail-fast.</strong> Unlike {@link #llmProvider}, this provider is
     * <em>not</em> added to the {@link LlmHealthGate}: a bad recall model must only degrade recall to the
     * RRF/bullets fast path (every recall step already falls back), never abort startup. Construction is
     * likewise tolerant — any error building it (e.g. a missing key) falls back to the primary with a
     * warning. No network call happens here; providers are probed lazily, per request.
     */
    @Bean
    public LlmProvider recallLlmProvider(
            ProviderFactory factory,
            AgentMemoryConfig config,
            @Qualifier("llmProvider") LlmProvider llmProvider,
            ObjectProvider<LlmRecallProperties> recallProps) {
        LlmRecallProperties props = recallProps.getIfAvailable();
        ProviderAuth recallAuth = props == null ? null : props.auth();
        return resolveRecallProvider(factory, config, llmProvider, recallAuth);
    }

    /**
     * Resolve the recall-axis provider from the optional {@code agent-memory.recall.llm.auth} block,
     * defaulting to the primary chat provider (issue #146). Package-private and side-effect-free (no
     * network) so the three branches are unit-testable without a context. Never throws — a malformed
     * recall auth degrades to {@code primary} with a warning, because recall is best-effort.
     *
     * <ul>
     *   <li><strong>{@code provider} set</strong> → an independent provider built from {@code recallAuth}
     *       (e.g. a cheap mini/flash model on its own API key), distinct from chat/consolidation.</li>
     *   <li><strong>only {@code model} set</strong> → the chat auth ({@link #resolveLlmAuth}, including the
     *       OAuth token-file default) with the model swapped — an alternate model on the same
     *       provider/credential.</li>
     *   <li><strong>nothing set</strong> → the same {@code primary} instance (no second client).</li>
     * </ul>
     */
    static LlmProvider resolveRecallProvider(ProviderFactory factory, AgentMemoryConfig config,
            LlmProvider primary, ProviderAuth recallAuth) {
        boolean providerSet = recallAuth != null && recallAuth.isConfigured();
        boolean modelOnly = !providerSet && recallAuth != null
                && recallAuth.model() != null && !recallAuth.model().isBlank();
        if (!providerSet && !modelOnly) {
            // Default: recall reuses the primary chat provider — no second provider, no behaviour change.
            log.info("Recall LLM provider: reusing the primary chat provider '{}' "
                    + "(set agent-memory.recall.llm.auth to route recall to a separate model).", primary.id());
            return primary;
        }
        try {
            ProviderAuth effective = providerSet
                    ? recallAuth
                    : withModel(resolveLlmAuth(config), recallAuth.model());
            LlmProvider provider = factory.createLlmProvider(effective);
            log.info("Recall LLM provider configured ({}): id={}, model={}",
                    providerSet ? "independent provider" : "chat auth, model override",
                    provider.id(), provider.model());
            return provider;
        } catch (RuntimeException e) {
            // Best-effort axis: never let a misconfigured recall model abort startup (it would only
            // degrade recall to the RRF/bullets fast path). Fall back to the primary and log loudly.
            log.warn("Could not build the recall LLM provider from 'agent-memory.recall.llm.auth' ({}). "
                    + "Falling back to the primary chat provider '{}' for recall; recall stays best-effort "
                    + "(degrading to RRF/bullets on any further error) and startup is unaffected.",
                    e.getMessage(), primary.id());
            return primary;
        }
    }

    /** A copy of {@code base} with only the model swapped (used for the recall model-only override). */
    private static ProviderAuth withModel(ProviderAuth base, String model) {
        return new ProviderAuth(base.provider(), base.apiKey(), base.baseUrl(), model, base.oauth());
    }

    /**
     * The optional embeddings provider, selected by {@code agent-memory.embeddings.auth.provider}.
     * Returns {@code null} (no usable embedder) when the embeddings axis is unconfigured, so the
     * server still starts with FTS + graph recall (DD-005). Downstream consumers must treat the
     * embedder as optional.
     *
     * <p>Note a chat provider may itself implement {@link Embedder} (the deterministic test double
     * does), which would make a bare by-type {@code Embedder} injection ambiguous. Consumers that
     * need this bean therefore inject it by name with {@code @Qualifier("embedder")} (see
     * {@link #llmHealthGate} and {@code LlmTestController}) rather than relying on type alone.
     */
    @Bean
    public Embedder embedder(ProviderFactory factory, AgentMemoryConfig config) {
        ProviderAuth auth = config.embeddings().auth();
        if (auth == null || !auth.isConfigured()) {
            log.warn("No embeddings provider configured (agent-memory.embeddings.auth); "
                    + "semantic recall will be disabled until one is set.");
            return null;
        }
        Embedder embedder = factory.createEmbedder(auth);
        log.info("Embeddings provider configured: id={}, model={}, dims={}",
                embedder.id(), embedder.model(), embedder.dimensions());
        return embedder;
    }

    /**
     * The startup health gate. Constructing this bean runs the probes; a failure of the required LLM
     * aborts startup. Declaring {@code llmProvider}/{@code embedder} as parameters makes the gate
     * depend on them so they are built first.
     */
    @Bean
    public LlmHealthGate llmHealthGate(
            @Qualifier("llmProvider") LlmProvider llmProvider,
            @Qualifier("embedder") ObjectProvider<Embedder> embedder) {
        // Inject by bean name: a test double implements both interfaces, so type alone is ambiguous.
        // ObjectProvider tolerates the embedder bean being null (optional axis, DD-005).
        LlmHealthGate gate = new LlmHealthGate(llmProvider, embedder.getIfAvailable());
        gate.verify();
        return gate;
    }
}
