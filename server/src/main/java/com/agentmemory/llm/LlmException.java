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
 *
 * <p>When the failure is a {@code 429} and the provider said when the quota window resets (a
 * {@code Retry-After} header, or {@code error.resets_in_seconds} in the ChatGPT
 * {@code usage_limit_reached} body), {@link #retryAfterMillis()} carries that hint (otherwise
 * {@code 0}). {@code LlmRateLimitGate} uses it to pause background LLM work until the reset instead
 * of burning doomed attempts (issue #148).
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;
    private final int httpStatus;
    private final long retryAfterMillis;

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
        this(message, cause, retryable, httpStatus, 0);
    }

    public LlmException(String message, Throwable cause, boolean retryable, int httpStatus,
            long retryAfterMillis) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfterMillis = Math.max(0, retryAfterMillis);
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

    /**
     * How long the provider asked us to wait before retrying, in milliseconds, or {@code 0} when the
     * response carried no usable hint. Only ever non-zero for a {@code 429}; parsed from the
     * {@code Retry-After} header or the ChatGPT {@code error.resets_in_seconds} body field.
     */
    public long retryAfterMillis() {
        return retryAfterMillis;
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
