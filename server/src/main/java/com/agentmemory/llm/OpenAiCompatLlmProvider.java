package com.agentmemory.llm;

/**
 * Placeholder for an OpenAI / OpenAI-compatible chat provider — a registered but <em>not yet
 * implemented</em> entry in the provider matrix, to be completed in issue #40. It exists so the
 * data-driven {@link ProviderFactory} already knows the {@code openai} / {@code openai-compat} keys
 * and so the shape of "add a provider without touching consumers" is demonstrable today.
 *
 * <p>Construction validates that auth is resolved (the typed boundary, invariant #14), but any
 * {@link #chat} or {@link #probe} call fails fast with a clear "not implemented" message rather than
 * silently degrading. Completing #40 means filling in the Chat Completions request/response mapping
 * (with {@code response_format = {type:"json_schema", json_schema: …}} for invariant #7) — no
 * consumer change required.
 */
public final class OpenAiCompatLlmProvider implements LlmProvider {

    /** Canonical provider key for the native OpenAI endpoint. */
    public static final String PROVIDER_KEY = "openai";

    /** Alias key for self-hosted / third-party OpenAI-compatible endpoints. */
    public static final String COMPAT_KEY = "openai-compat";

    public static final String DEFAULT_MODEL = "gpt-5.5";

    private final String key;
    private final String model;

    public OpenAiCompatLlmProvider(String key, com.agentmemory.config.ProviderAuth auth) {
        this.key = key;
        // Resolve the key up front to honour the auth boundary even though calls are stubbed.
        auth.requireApiKey(key);
        this.model = auth.modelOr(DEFAULT_MODEL);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        throw unimplemented();
    }

    @Override
    public String id() {
        return key;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void probe() {
        throw unimplemented();
    }

    private LlmException unimplemented() {
        return LlmException.permanent(
                "The '" + key + "' LLM provider is not implemented yet (planned for issue #40). "
                        + "Configure 'agent-memory.llm.auth.provider=anthropic' (or 'test'), or implement "
                        + "this provider before selecting it.", null);
    }
}
