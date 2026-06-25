package com.agentmemory.config;

/**
 * Typed credentials for an LLM / embeddings provider, resolved from configuration <em>before</em>
 * any provider client is constructed (invariant #14; DD-005).
 *
 * <p>This is the placeholder the LLM wiring in issue #6 slots into without touching call sites:
 * configuration already hands subsystems a {@code ProviderAuth} instead of letting them read raw
 * environment variables. The four fields below are the resolved boundary; #6 adds the helpers
 * ({@link #providerKey()}, {@link #modelOr(String)}, {@link #requireApiKey()}) that the
 * {@code com.agentmemory.llm} factory uses to build provider clients <em>after</em> auth is
 * resolved (invariant #14), keeping the contract "auth is a resolved, typed value, never an ad-hoc
 * {@code System.getenv} read" intact.
 *
 * <p>All fields are nullable at this stage: the configuration surface for #2 only needs to
 * <em>carry</em> credentials, not validate provider reachability (that is #6, explicitly out of
 * scope here). {@code apiKey} is held as a {@code char[]}-free {@link String} for binding
 * simplicity; redaction in {@link #toString()} keeps it out of logs.
 *
 * @param provider provider key (e.g. {@code anthropic}, {@code openai}, {@code openai-compat},
 *                 {@code gemini}); {@code null} until configured.
 * @param apiKey   secret API key; {@code null} when the provider needs none (e.g. a local model).
 * @param baseUrl  override base URL for OpenAI-compatible / self-hosted endpoints; {@code null}
 *                 to use the provider default.
 * @param model    model identifier to use for this axis; {@code null} until configured.
 */
public record ProviderAuth(String provider, String apiKey, String baseUrl, String model) {

    /** An empty, unconfigured placeholder — the default until #6 wires real providers. */
    public static final ProviderAuth NONE = new ProviderAuth(null, null, null, null);

    /** @return {@code true} when at least a provider key has been configured. */
    public boolean isConfigured() {
        return provider != null && !provider.isBlank();
    }

    /** @return {@code true} when a non-blank API key is present. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * The normalized provider key used to select a provider implementation in the
     * {@code com.agentmemory.llm} factory: {@link #provider()} lower-cased and trimmed, or
     * {@code null} when unconfigured. Normalizing here means call sites compare against a single
     * canonical form (e.g. {@code "anthropic"}) regardless of how the value was cased in config.
     */
    public String providerKey() {
        return provider == null ? null : provider.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** @return the configured {@link #model()} if present, else {@code fallback}. */
    public String modelOr(String fallback) {
        return (model == null || model.isBlank()) ? fallback : model;
    }

    /**
     * The resolved API key, or a failure if none was configured — the typed boundary that replaces a
     * raw {@code System.getenv} read at the provider-construction site (invariant #14). Callers that
     * build a client requiring a key invoke this once, up front, so a missing key fails fast with an
     * actionable message instead of surfacing later as an opaque {@code 401}.
     *
     * @param providerLabel provider name for the error message (e.g. {@code "anthropic"}).
     * @return the non-blank API key.
     * @throws IllegalStateException if no API key is configured.
     */
    public String requireApiKey(String providerLabel) {
        if (!hasApiKey()) {
            throw new IllegalStateException(
                    "No API key configured for the '" + providerLabel + "' provider. Set it via config "
                            + "(e.g. 'agent-memory.llm.auth.api-key') or the corresponding environment "
                            + "variable; provider keys are never read from the environment at call sites.");
        }
        return apiKey;
    }

    @Override
    public String toString() {
        // Never leak the secret; this value flows through startup logs and error messages.
        return "ProviderAuth[provider=" + provider
                + ", apiKey=" + (hasApiKey() ? "***" : "<none>")
                + ", baseUrl=" + baseUrl
                + ", model=" + model + "]";
    }
}
