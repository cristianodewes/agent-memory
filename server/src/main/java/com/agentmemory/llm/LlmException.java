package com.agentmemory.llm;

/**
 * Raised when an LLM or embeddings provider call fails, or when a required provider is unreachable.
 *
 * <p>Two shapes matter to callers:
 * <ul>
 *   <li>{@link #isRetryable()} {@code == true} — a transient failure (network blip, {@code 429},
 *       {@code 5xx}); the caller may retry with backoff.</li>
 *   <li>{@link #isRetryable()} {@code == false} — a permanent failure (bad API key, malformed
 *       request, schema-validation failure); retrying is pointless.</li>
 * </ul>
 *
 * <p>The startup health gate ({@code LlmHealthGate}) turns an exception from the configured,
 * <em>required</em> chat provider into a fail-fast abort (invariant #13); an exception from the
 * optional embeddings probe is logged as a degraded-recall warning instead (DD-005).
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message) {
        this(message, null, false);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public LlmException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** A transient, retryable failure (timeout, {@code 429}, {@code 5xx}) versus a permanent one. */
    public boolean isRetryable() {
        return retryable;
    }

    /** Factory for a transient failure the caller may retry. */
    public static LlmException retryable(String message, Throwable cause) {
        return new LlmException(message, cause, true);
    }

    /** Factory for a permanent failure; retrying will not help. */
    public static LlmException permanent(String message, Throwable cause) {
        return new LlmException(message, cause, false);
    }
}
