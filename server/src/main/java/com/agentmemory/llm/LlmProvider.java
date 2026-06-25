package com.agentmemory.llm;

/**
 * The pluggable chat/completion provider behind which every knowledge-shaping flow runs — the
 * <strong>required</strong> dependency at the heart of the product (DD-005, invariant #13).
 * Consolidation (#18/#19), recall re-ranking (#21), handoffs (#22) and chat (#37) all call this
 * interface; none of them know which concrete provider is configured.
 *
 * <p>Implementations:
 * <ul>
 *   <li>resolve credentials from a typed {@link com.agentmemory.config.ProviderAuth} <em>before</em>
 *       the client is constructed (invariant #14) — never a raw {@code System.getenv} at a call
 *       site;</li>
 *   <li>use the provider-native structured-JSON output mode when {@link ChatRequest#schema()} is
 *       present (invariant #7), validating the reply against the schema;</li>
 *   <li>translate transport/HTTP failures into {@link LlmException}, marking transient ones
 *       {@link LlmException#isRetryable() retryable}.</li>
 * </ul>
 *
 * <p>New providers are registered data-drivenly in {@code ProviderFactory} keyed by the configured
 * {@code provider} string, so issue #40 can add OpenAI/Gemini without touching any consumer.
 */
public interface LlmProvider {

    /**
     * Send a chat request and return the reply.
     *
     * @param request the conversation plus optional structured-output schema.
     * @return the reply, carrying the denormalized {@code provider}/{@code model}.
     * @throws LlmException on a provider/transport failure or a structured-output validation failure.
     */
    ChatResponse chat(ChatRequest request);

    /**
     * The provider key this instance serves (e.g. {@code anthropic}). Stable, lower-case; used as
     * the denormalized {@code provider} value and as the data-driven factory key.
     */
    String id();

    /**
     * The concrete model id calls will use, resolved from config at construction time. Surfaced for
     * logging, the {@code llm-test} endpoint, and denormalization.
     */
    String model();

    /**
     * Cheap liveness/credential probe run once by the startup health gate. A successful return means
     * the provider is reachable and the credentials are accepted; a failure must throw
     * {@link LlmException}. Because the LLM is required, a probe failure aborts startup (fail-fast,
     * invariant #13). Keep it minimal (a tiny structured request or a models lookup) to bound boot
     * latency.
     *
     * @throws LlmException if the provider is unreachable or rejects the credentials.
     */
    void probe();
}
