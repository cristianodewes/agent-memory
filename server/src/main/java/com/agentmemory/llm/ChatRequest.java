package com.agentmemory.llm;

import java.time.Duration;
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
 * <p>Two optional, provider-neutral hints support the latency-sensitive recall path (issue #130):
 * <ul>
 *   <li>{@link #reasoningEffort()} asks the model to spend less hidden reasoning ({@code null} =
 *       unchanged); only the OpenAI OAuth provider acts on it (see {@link ReasoningEffort}).</li>
 *   <li>{@link #requestTimeout()} overrides the provider's default per-call HTTP timeout for <em>this
 *       call only</em> ({@code null} = the provider-wide default). The recall steps derive a short
 *       timeout from their remaining wall-clock budget so a slow LLM call is cancelled inside the
 *       budget instead of running for the provider's full (e.g. 300s) timeout; heavy chat/consolidation
 *       leaves it {@code null} and keeps the generous default.</li>
 * </ul>
 *
 * @param messages        the ordered conversation; must be non-empty.
 * @param schema          optional structured-output schema; {@code null} for free-form text.
 * @param maxOutputTokens soft cap on reply length; must be positive.
 * @param reasoningEffort optional reasoning-effort hint; {@code null} for the provider default.
 * @param requestTimeout  optional per-call HTTP timeout override; {@code null} for the provider
 *                        default. Must be positive when present.
 */
public record ChatRequest(
        List<ChatMessage> messages,
        JsonSchema schema,
        int maxOutputTokens,
        ReasoningEffort reasoningEffort,
        Duration requestTimeout) {

    /** Default reply cap — generous enough for a consolidated page or handoff, safe for timeouts. */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    public ChatRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("ChatRequest.messages must be non-empty");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("ChatRequest.maxOutputTokens must be positive");
        }
        if (requestTimeout != null && (requestTimeout.isZero() || requestTimeout.isNegative())) {
            throw new IllegalArgumentException(
                    "ChatRequest.requestTimeout must be positive when present, was " + requestTimeout);
        }
        messages = List.copyOf(messages);
    }

    /**
     * Backwards-compatible constructor for the common case: no reasoning hint and the provider's
     * default per-call timeout. Keeps every existing {@code new ChatRequest(messages, schema, max)}
     * call site working unchanged.
     */
    public ChatRequest(List<ChatMessage> messages, JsonSchema schema, int maxOutputTokens) {
        this(messages, schema, maxOutputTokens, null, null);
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

    /**
     * @return a copy carrying the given reasoning-effort hint (the recall steps set this to
     *     {@link ReasoningEffort#MINIMAL}). A {@code null} effort yields the unchanged-behavior request.
     */
    public ChatRequest withReasoningEffort(ReasoningEffort effort) {
        return new ChatRequest(messages, schema, maxOutputTokens, effort, requestTimeout);
    }

    /**
     * @return a copy bounded by a per-call HTTP timeout (the recall budget axis). A {@code null} timeout
     *     restores the provider-wide default.
     */
    public ChatRequest withRequestTimeout(Duration timeout) {
        return new ChatRequest(messages, schema, maxOutputTokens, reasoningEffort, timeout);
    }
}
