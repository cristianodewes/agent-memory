package com.agentmemory.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cost controls for "chat with your memory" (issue #37), bound under {@code agent-memory.chat}. Kept a
 * focused, feature-local binding rather than swelling the central
 * {@link com.agentmemory.config.AgentMemoryProperties} (deliberately thin), exactly as
 * {@link com.agentmemory.llmrecall.LlmRecallProperties} owns recall's tuning and the sanitizer/ingest
 * own theirs.
 *
 * <p>These knobs are the "cost controls (context caps, retrieval-K limits)" the acceptance asks for.
 * Retrieval is capped at {@code retrievalK} grounding hits; the assembled context is truncated to
 * {@code maxContextChars}; the model reply is capped at {@code maxOutputTokens}. Together they bound
 * the prompt size and the generation cost of a single chat turn.
 *
 * @param enabled         master switch for the chat feature; when {@code false} the chat service and
 *     endpoint do not register and the endpoint answers {@code 503}. Default {@code true}.
 * @param retrievalK      hard cap on how many recall hits are used to ground the answer (the RAG
 *     context window). Bounds how many pages are fetched and concatenated. Must be {@code > 0}.
 *     Default 6.
 * @param maxContextChars hard upper bound on the assembled grounding context (concatenated page
 *     excerpts); the context is truncated to fit so the prompt stays bounded regardless of page size.
 *     Must be {@code > 0}. Default 6000.
 * @param maxOutputTokens soft cap on the model's reply length for one chat turn. Must be {@code > 0}.
 *     Default 1024.
 */
@ConfigurationProperties(prefix = "agent-memory.chat")
public record ChatProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("6") int retrievalK,
        @DefaultValue("6000") int maxContextChars,
        @DefaultValue("1024") int maxOutputTokens) {

    public ChatProperties {
        if (retrievalK <= 0) {
            throw new IllegalArgumentException("agent-memory.chat.retrieval-k must be > 0, was " + retrievalK);
        }
        if (maxContextChars <= 0) {
            throw new IllegalArgumentException(
                    "agent-memory.chat.max-context-chars must be > 0, was " + maxContextChars);
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException(
                    "agent-memory.chat.max-output-tokens must be > 0, was " + maxOutputTokens);
        }
    }
}
