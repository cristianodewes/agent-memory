package com.agentmemory.llm;

import com.agentmemory.config.ProviderAuth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The data-driven registry that turns a typed {@link ProviderAuth} into a concrete
 * {@link LlmProvider} or {@link Embedder}, keyed by the normalized provider string
 * ({@link ProviderAuth#providerKey()}).
 *
 * <p>This is the seam the issue calls for — "make the provider matrix data-driven so #40 can add
 * providers without touching consumers". Adding a provider is a single registration in the maps
 * below (or, later, a registered {@code ProviderRegistration} bean); the health gate, the
 * {@code llm-test} endpoint, and every downstream consumer keep calling the interfaces and never
 * learn the new key. Auth is fully resolved before any client is constructed (invariant #14): the
 * factory only ever receives a typed {@code ProviderAuth}, never reads the environment.
 *
 * <p>The {@code test} key returns a single shared {@link TestDoubleProvider} that satisfies both the
 * chat and embedding axes, so a fully offline, deterministic configuration is one config change.
 */
public final class ProviderFactory {

    /** Registry of chat-provider builders, keyed by normalized provider id. */
    private final Map<String, Function<ProviderAuth, LlmProvider>> llmBuilders = new LinkedHashMap<>();

    /** Registry of embedder builders, keyed by normalized provider id. */
    private final Map<String, Function<ProviderAuth, Embedder>> embedderBuilders = new LinkedHashMap<>();

    /** A process-wide shared test double so the {@code test} chat + embedding axes are the same object. */
    private final TestDoubleProvider sharedTestDouble;

    public ProviderFactory() {
        this(TestDoubleProvider.create());
    }

    /** Construct with a specific test double (lets tests inject a {@code failProbe} or scripted one). */
    public ProviderFactory(TestDoubleProvider sharedTestDouble) {
        this.sharedTestDouble = sharedTestDouble;
        registerDefaults();
    }

    /** Wire the built-in provider matrix. New providers (issue #40) are added here. */
    private void registerDefaults() {
        // --- chat providers ---
        registerLlm(AnthropicLlmProvider.PROVIDER_KEY, AnthropicLlmProvider::new);
        registerLlm(OpenAiCompatLlmProvider.PROVIDER_KEY,
                auth -> new OpenAiCompatLlmProvider(OpenAiCompatLlmProvider.PROVIDER_KEY, auth));
        registerLlm(OpenAiCompatLlmProvider.COMPAT_KEY,
                auth -> new OpenAiCompatLlmProvider(OpenAiCompatLlmProvider.COMPAT_KEY, auth));
        registerLlm(OpenAiOAuthLlmProvider.PROVIDER_KEY, OpenAiOAuthLlmProvider::new);
        registerLlm(GeminiLlmProvider.PROVIDER_KEY, GeminiLlmProvider::new);
        registerLlm(TestDoubleProvider.PROVIDER_KEY, auth -> sharedTestDouble);

        // --- embedders ---
        registerEmbedder(VoyageEmbedder.PROVIDER_KEY, VoyageEmbedder::new);
        registerEmbedder(OpenAiEmbedder.PROVIDER_KEY, OpenAiEmbedder::new);
        registerEmbedder(GoogleEmbedder.PROVIDER_KEY, GoogleEmbedder::new);
        registerEmbedder(TestDoubleProvider.PROVIDER_KEY, auth -> sharedTestDouble);
    }

    /** Register (or override) a chat-provider builder under {@code key}. */
    public void registerLlm(String key, Function<ProviderAuth, LlmProvider> builder) {
        llmBuilders.put(normalize(key), builder);
    }

    /** Register (or override) an embedder builder under {@code key}. */
    public void registerEmbedder(String key, Function<ProviderAuth, Embedder> builder) {
        embedderBuilders.put(normalize(key), builder);
    }

    /**
     * Build the chat provider selected by {@code auth.provider}.
     *
     * @param auth resolved chat-axis credentials; {@code provider} selects the implementation.
     * @return the provider instance.
     * @throws LlmException if no provider is configured or the configured key is unknown.
     */
    public LlmProvider createLlmProvider(ProviderAuth auth) {
        String key = requireKey(auth, "llm",
                "Set 'agent-memory.llm.auth.provider' to one of " + llmProviderKeys() + ".");
        Function<ProviderAuth, LlmProvider> builder = llmBuilders.get(key);
        if (builder == null) {
            throw LlmException.permanent(
                    "Unknown LLM provider '" + key + "'. Known providers: " + llmProviderKeys() + ".", null);
        }
        return builder.apply(auth);
    }

    /**
     * Build the embedder selected by {@code auth.provider}.
     *
     * @param auth resolved embeddings-axis credentials; {@code provider} selects the implementation.
     * @return the embedder instance.
     * @throws LlmException if no provider is configured or the configured key is unknown.
     */
    public Embedder createEmbedder(ProviderAuth auth) {
        String key = requireKey(auth, "embeddings",
                "Set 'agent-memory.embeddings.auth.provider' to one of " + embedderKeys() + ".");
        Function<ProviderAuth, Embedder> builder = embedderBuilders.get(key);
        if (builder == null) {
            throw LlmException.permanent(
                    "Unknown embeddings provider '" + key + "'. Known providers: " + embedderKeys() + ".", null);
        }
        return builder.apply(auth);
    }

    /** The registered chat-provider keys (for diagnostics / error messages). */
    public Set<String> llmProviderKeys() {
        return Set.copyOf(llmBuilders.keySet());
    }

    /** The registered embedder keys (for diagnostics / error messages). */
    public Set<String> embedderKeys() {
        return Set.copyOf(embedderBuilders.keySet());
    }

    private static String requireKey(ProviderAuth auth, String axis, String hint) {
        if (auth == null || !auth.isConfigured()) {
            throw LlmException.permanent(
                    "No " + axis + " provider configured. " + hint, null);
        }
        return auth.providerKey();
    }

    private static String normalize(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("provider key must not be blank");
        }
        return key.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
