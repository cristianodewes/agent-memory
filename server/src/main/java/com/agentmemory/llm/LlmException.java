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
 *
 * <p>When the failure came from a non-2xx HTTP response, {@link #httpStatus()} carries that status
 * code (otherwise {@code 0}). The OAuth chat path (issue #113) reads it to recognize a {@code 401}
 * and trigger a one-shot token refresh + retry before giving up.
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;
    private final int httpStatus;

    public LlmException(String message) {
        this(message, null, false, 0);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, false, 0);
    }

    public LlmException(String message, Throwable cause, boolean retryable) {
        this(message, cause, retryable, 0);
    }

    public LlmException(String message, Throwable cause, boolean retryable, int httpStatus) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    /** A transient, retryable failure (timeout, {@code 429}, {@code 5xx}) versus a permanent one. */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * The HTTP status code that produced this failure, or {@code 0} when it did not originate from a
     * non-2xx HTTP response (a transport error, a parse failure, a validation failure, …). Lets a
     * caller distinguish, e.g., a {@code 401} (refresh the OAuth token and retry once) from a generic
     * permanent failure.
     */
    public int httpStatus() {
        return httpStatus;
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
