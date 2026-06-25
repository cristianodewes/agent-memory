package com.agentmemory.llm;

/**
 * The result of a {@link LlmProvider} chat call: the reply text plus the denormalized
 * {@code provider} / {@code model} that produced it and the token usage.
 *
 * <p>When the originating {@link ChatRequest} carried a {@link JsonSchema}, {@link #text()} is a
 * JSON document that has been validated against that schema by the provider (invariant #7), so
 * callers may parse it directly. For a free-form request it is plain text.
 *
 * @param text            the reply; structured JSON when a schema was requested, else free text.
 * @param provider        the provider key that served the call (e.g. {@code anthropic}).
 * @param model           the concrete model id that produced the reply.
 * @param inputTokens     prompt tokens billed, or {@code -1} if the provider did not report them.
 * @param outputTokens    completion tokens billed, or {@code -1} if not reported.
 */
public record ChatResponse(String text, String provider, String model, int inputTokens, int outputTokens) {

    public ChatResponse {
        if (text == null) {
            throw new IllegalArgumentException("ChatResponse.text must not be null");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("ChatResponse.provider must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("ChatResponse.model must not be blank");
        }
    }
}
