package com.agentmemory.config;

/**
 * Typed credentials for an LLM / embeddings provider, resolved from configuration <em>before</em>
 * any provider client is constructed (invariant #14; DD-005).
 *
 * <p>This is the placeholder the LLM wiring in issue #6 slots into without touching call sites:
 * configuration already hands subsystems a {@code ProviderAuth} instead of letting them read raw
 * environment variables. Fields are intentionally minimal for now — #6 may extend the provider
 * model (e.g. organization id, extra headers) — but the contract that "auth is a resolved, typed
 * value, never an ad-hoc {@code System.getenv} read" is established here.
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

    @Override
    public String toString() {
        // Never leak the secret; this value flows through startup logs and error messages.
        return "ProviderAuth[provider=" + provider
                + ", apiKey=" + (hasApiKey() ? "***" : "<none>")
                + ", baseUrl=" + baseUrl
                + ", model=" + model + "]";
    }
}
