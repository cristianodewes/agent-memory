package com.agentmemory.llm;

import java.util.List;

/**
 * A single request to an {@link LlmProvider}: the conversation, an output cap, and — when the caller
 * needs a machine-readable result — an optional {@link JsonSchema} that constrains the reply to
 * structured JSON (invariant #7).
 *
 * <p>A {@code null} {@link #schema()} means free-form text is acceptable (e.g. the "chat with your
 * memory" surface). Every knowledge-shaping caller (consolidation, handoffs, recall re-ranking,
 * lint) supplies a schema so the result can be parsed deterministically.
 *
 * <p>The model is intentionally <em>not</em> on the request: it is resolved once from typed config
 * ({@link com.agentmemory.config.ProviderAuth#model()}) when the provider client is built, so call
 * sites never hard-code a model id. {@code maxOutputTokens} bounds the reply; providers clamp it to
 * their own ceiling.
 *
 * @param messages        the ordered conversation; must be non-empty.
 * @param schema          optional structured-output schema; {@code null} for free-form text.
 * @param maxOutputTokens soft cap on reply length; must be positive.
 */
public record ChatRequest(List<ChatMessage> messages, JsonSchema schema, int maxOutputTokens) {

    /** Default reply cap — generous enough for a consolidated page or handoff, safe for timeouts. */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    public ChatRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("ChatRequest.messages must be non-empty");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("ChatRequest.maxOutputTokens must be positive");
        }
        messages = List.copyOf(messages);
    }

    /** @return {@code true} when a structured-JSON reply is required. */
    public boolean wantsStructuredOutput() {
        return schema != null;
    }

    /** A free-form text request with the default output cap. */
    public static ChatRequest text(List<ChatMessage> messages) {
        return new ChatRequest(messages, null, DEFAULT_MAX_OUTPUT_TOKENS);
    }

    /** A structured-JSON request constrained by {@code schema}, with the default output cap. */
    public static ChatRequest structured(List<ChatMessage> messages, JsonSchema schema) {
        if (schema == null) {
            throw new IllegalArgumentException("structured() requires a non-null schema");
        }
        return new ChatRequest(messages, schema, DEFAULT_MAX_OUTPUT_TOKENS);
    }
}
