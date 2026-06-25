package com.agentmemory.handoff;

/**
 * Raised when an LLM-generated handoff cannot be produced — specifically when the model's reply does
 * not parse into the typed handoff shape (invariant #7: structured JSON only). Distinct from
 * {@link com.agentmemory.llm.LlmException} (a transport/provider failure), which propagates as-is;
 * this signals a content/shape problem in an otherwise-successful call. A {@link RuntimeException} so
 * it surfaces to the endpoint/tool as a clear error rather than persisting a malformed handoff.
 */
public class HandoffException extends RuntimeException {

    public HandoffException(String message) {
        super(message);
    }

    public HandoffException(String message, Throwable cause) {
        super(message, cause);
    }
}
