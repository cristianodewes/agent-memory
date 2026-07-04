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
     * Shared runtime rate-limit state for the required chat provider (issue #148). The primary
     * provider records every call outcome here (via {@link RateLimitTrackingLlmProvider}); the
     * background dispatchers (consolidation, handoff) consult it before firing so an exhausted quota
     * pauses the queues instead of burning one doomed LLM call per queued task. {@link Primary} since
     * #150 added the per-axis {@link #reviewLlmRateLimitGate}: a bare by-type injection (consolidation,
     * handoff) must keep resolving to the chat provider's gate.
     */
    @Bean
    @Primary
    public LlmRateLimitGate llmRateLimitGate() {
        return new LlmRateLimitGate();
    }

    /**
     * The rate-limit gate the auto-improve review engine consults (issue #150). When review reuses the
     * primary credential (the default, or a model-only override) this IS the shared {@link
     * #llmRateLimitGate} — one quota, one gate. When {@code review.auth} configures an independent
     * provider (own credential, own quota) the review axis gets its <em>own</em> gate, so review 429s
     * pause the review loop without pausing the primary's queues, and vice versa.
     */
    @Bean
    public LlmRateLimitGate reviewLlmRateLimitGate(
            LlmRateLimitGate llmRateLimitGate,
            ObjectProvider<com.agentmemory.autoimprove.AutoImproveProperties> reviewProps) {
        com.agentmemory.autoimprove.AutoImproveProperties props = reviewProps.getIfAvailable();
        ProviderAuth auth = props == null || props.review() == null ? null : props.review().auth();
        boolean independent = auth != null && auth.isConfigured();
        return independent ? new LlmRateLimitGate() : llmRateLimitGate;
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
    public LlmProvider llmProvider(ProviderFactory factory, AgentMemoryConfig config,
            LlmRateLimitGate rateLimitGate) {
        ProviderAuth auth = resolveLlmAuth(config);
        LlmProvider provider = factory.createLlmProvider(auth);
        log.info("LLM provider configured: id={}, model={}", provider.id(), provider.model());
        // Wrapped so every call outcome feeds the shared rate-limit gate (issue #148); the decorator
        // is otherwise a pure pass-through and preserves id()/model()/probe() semantics.
        return new RateLimitTrackingLlmProvider(provider, rateLimitGate);
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
        return resolveSecondaryProvider(
                factory, config, primary, recallAuth, "Recall", "agent-memory.recall.llm.auth");
    }

    /**
     * Resolve an optional secondary-axis provider (recall #146, auto-improve review #150) from its own
     * auth block, defaulting to the primary chat provider. Never throws — a malformed secondary auth
     * degrades to {@code primary} with a warning, because every secondary axis is best-effort.
     *
     * <ul>
     *   <li><strong>{@code provider} set</strong> → an independent provider built from {@code auth}
     *       (e.g. a cheap mini/flash model on its own API key), distinct from chat/consolidation.</li>
     *   <li><strong>only {@code model} set</strong> → the chat auth ({@link #resolveLlmAuth}, including the
     *       OAuth token-file default) with the model swapped — an alternate model on the same
     *       provider/credential.</li>
     *   <li><strong>nothing set</strong> → the same {@code primary} instance (no second client).</li>
     * </ul>
     */
    static LlmProvider resolveSecondaryProvider(ProviderFactory factory, AgentMemoryConfig config,
            LlmProvider primary, ProviderAuth auth, String axis, String configKey) {
        boolean providerSet = auth != null && auth.isConfigured();
        boolean modelOnly = !providerSet && auth != null
                && auth.model() != null && !auth.model().isBlank();
        if (!providerSet && !modelOnly) {
            // Default: the axis reuses the primary chat provider — no second provider, no behaviour change.
            log.info("{} LLM provider: reusing the primary chat provider '{}' "
                    + "(set {} to route it to a separate model).", axis, primary.id(), configKey);
            return primary;
        }
        try {
            ProviderAuth effective = providerSet
                    ? auth
                    : withModel(resolveLlmAuth(config), auth.model());
            LlmProvider provider = factory.createLlmProvider(effective);
            log.info("{} LLM provider configured ({}): id={}, model={}",
                    axis, providerSet ? "independent provider" : "chat auth, model override",
                    provider.id(), provider.model());
            return provider;
        } catch (RuntimeException e) {
            // Best-effort axis: never let a misconfigured secondary model abort startup. Fall back to
            // the primary and log loudly.
            log.warn("Could not build the {} LLM provider from '{}' ({}). "
                    + "Falling back to the primary chat provider '{}'; the axis stays best-effort "
                    + "and startup is unaffected.",
                    axis, configKey, e.getMessage(), primary.id());
            return primary;
        }
    }

    /** A copy of {@code base} with only the model swapped (used for the recall model-only override). */
    private static ProviderAuth withModel(ProviderAuth base, String model) {
        return new ProviderAuth(base.provider(), base.apiKey(), base.baseUrl(), model, base.oauth());
    }

    /**
     * The provider the auto-improve session-review engine uses (issue #150), mirroring the recall seam
     * (#146): route the background review calls to a cheaper model via
     * {@code agent-memory.auto-improve.review.auth}, defaulting to the primary chat provider. Whatever
     * the resolution, the returned provider always feeds a {@link LlmRateLimitGate} (#148): the default
     * reuse IS the decorated primary; a model-only or independent resolution is wrapped here with the
     * {@link #reviewLlmRateLimitGate} — the shared gate for a shared credential (model-only), a
     * per-axis gate for an independent one, so review 429s always pause the review loop instead of
     * burning claim attempts. Best-effort like recall: never probed at startup, and a misconfigured
     * block falls back to the primary with a warning.
     *
     * <p>The {@code AutoImproveProperties} carrier is injected via {@link ObjectProvider} because it is
     * registered by the (auto-configured) {@code AutoImproveConfiguration}, which need not be present
     * when this module is loaded in isolation; when absent the review axis simply reuses the primary.
     */
    @Bean
    public LlmProvider reviewLlmProvider(
            ProviderFactory factory,
            AgentMemoryConfig config,
            @Qualifier("llmProvider") LlmProvider llmProvider,
            @Qualifier("reviewLlmRateLimitGate") LlmRateLimitGate reviewGate,
            ObjectProvider<com.agentmemory.autoimprove.AutoImproveProperties> reviewProps) {
        com.agentmemory.autoimprove.AutoImproveProperties props = reviewProps.getIfAvailable();
        ProviderAuth auth = props == null || props.review() == null ? null : props.review().auth();
        LlmProvider resolved = resolveSecondaryProvider(
                factory, config, llmProvider, auth, "Review", "agent-memory.auto-improve.review.auth");
        if (resolved == llmProvider) {
            return resolved; // the primary is already gate-decorated; wrapping again would double-count
        }
        return new RateLimitTrackingLlmProvider(resolved, reviewGate);
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
