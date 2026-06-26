package com.agentmemory.llm;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.ProviderAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     */
    @Bean
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
            @Qualifier("embedder") org.springframework.beans.factory.ObjectProvider<Embedder> embedder) {
        // Inject by bean name: a test double implements both interfaces, so type alone is ambiguous.
        // ObjectProvider tolerates the embedder bean being null (optional axis, DD-005).
        LlmHealthGate gate = new LlmHealthGate(llmProvider, embedder.getIfAvailable());
        gate.verify();
        return gate;
    }
}
