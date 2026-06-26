package com.agentmemory.config;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed credentials for an LLM / embeddings provider, resolved from configuration <em>before</em>
 * any provider client is constructed (invariant #14; DD-005).
 *
 * <p>This is the placeholder the LLM wiring in issue #6 slots into without touching call sites:
 * configuration already hands subsystems a {@code ProviderAuth} instead of letting them read raw
 * environment variables. The fields below are the resolved boundary; #6 adds the helpers
 * ({@link #providerKey()}, {@link #modelOr(String)}, {@link #requireApiKey(String)}) that the
 * {@code com.agentmemory.llm} factory uses to build provider clients <em>after</em> auth is
 * resolved (invariant #14), keeping the contract "auth is a resolved, typed value, never an ad-hoc
 * {@code System.getenv} read" intact.
 *
 * <p>Most fields are nullable at this stage: the configuration surface only needs to <em>carry</em>
 * credentials, not validate provider reachability (that is #6). {@code apiKey} is held as a
 * {@code char[]}-free {@link String} for binding simplicity; redaction in {@link #toString()} keeps
 * it out of logs.
 *
 * <p>The nested {@link OAuth} block (issue #113) points the {@code openai-oauth} provider at the
 * <em>token file</em> holding its ChatGPT/Codex OAuth credential. Unlike a static {@link #apiKey},
 * that long-lived secret is not pasted into YAML: it is written by {@code agent-memory auth login
 * openai-oauth} and refreshed/rewritten by the server, so the config only carries the file path
 * (defaulted under the data dir when unset).
 *
 * @param provider provider key (e.g. {@code anthropic}, {@code openai}, {@code openai-compat},
 *                 {@code openai-oauth}, {@code gemini}); {@code null} until configured.
 * @param apiKey   secret API key; {@code null} when the provider needs none (e.g. a local model, or
 *                 the OAuth provider, which uses {@link #oauth} + a token file instead).
 * @param baseUrl  override base URL for OpenAI-compatible / self-hosted endpoints (and the Codex
 *                 Responses endpoint for {@code openai-oauth}); {@code null} to use the default.
 * @param model    model identifier to use for this axis; {@code null} until configured.
 * @param oauth    OAuth token-file pointer for the {@code openai-oauth} provider; {@link OAuth#NONE}
 *                 (the default) for every static-key provider.
 */
public record ProviderAuth(String provider, String apiKey, String baseUrl, String model,
        @DefaultValue OAuth oauth) {

    /** An empty, unconfigured placeholder. */
    public static final ProviderAuth NONE = new ProviderAuth(null, null, null, null, OAuth.NONE);

    // The canonical constructor is the one Spring binds @ConfigurationProperties through; mark it
    // explicitly because the convenience 4-arg constructor below would otherwise make the binding
    // constructor ambiguous (issue #113 added the 5th OAuth field — mirrors AgentMemoryProperties.Auth).
    @ConstructorBinding
    public ProviderAuth {
        if (oauth == null) {
            oauth = OAuth.NONE;
        }
    }

    /**
     * Backwards-compatible constructor without the OAuth sub-block (issue #113 added the 5th field).
     * The many call sites and tests predating OAuth use this 4-arg form; it defaults to {@link OAuth#NONE}.
     */
    public ProviderAuth(String provider, String apiKey, String baseUrl, String model) {
        this(provider, apiKey, baseUrl, model, OAuth.NONE);
    }

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
                + ", model=" + model
                + ", oauth=" + oauth + "]";
    }

    /**
     * Token-file pointer for the {@code openai-oauth} chat provider (issue #113). The provider's
     * long-lived ChatGPT/Codex refresh token is not configured here — it lives in the token file the
     * {@code agent-memory auth login openai-oauth} flow writes (DD-001 — the server holds and refreshes
     * the credential). This block only carries the path; when {@link #tokenFile} is blank the wiring
     * defaults it to {@code <data-dir>/auth.json}. The path is not a secret, so it is logged plainly.
     *
     * @param tokenFile filesystem path of the shared OAuth token file; blank ⇒ default under the data dir.
     */
    public record OAuth(@DefaultValue("") String tokenFile) {

        /** The disabled default — no token-file override (every static-key provider uses this). */
        public static final OAuth NONE = new OAuth("");

        /** @return whether a non-blank token-file path was configured. */
        public boolean hasTokenFile() {
            return tokenFile != null && !tokenFile.isBlank();
        }

        /** @return whether anything meaningful was configured in this block. */
        public boolean isConfigured() {
            return hasTokenFile();
        }

        @Override
        public String toString() {
            return "OAuth[tokenFile=" + (hasTokenFile() ? tokenFile : "<default>") + "]";
        }
    }
}
