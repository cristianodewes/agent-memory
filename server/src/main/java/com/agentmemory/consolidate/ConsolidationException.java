package com.agentmemory.consolidate;

/**
 * Raised when session consolidation cannot complete (issue #18): the LLM reply was malformed or
 * failed validation, or a downstream write failed. Consolidation is always LLM-driven with no
 * rule-based fallback (DD-005, invariant #13), so a genuine failure is surfaced rather than silently
 * producing a degraded page — the caller (the session-end trigger) decides whether to retry.
 *
 * <p>Unchecked so it propagates cleanly through the trigger/transaction without forcing a checked
 * signature on every seam; the message carries the actionable cause.
 */
public class ConsolidationException extends RuntimeException {

    public ConsolidationException(String message) {
        super(message);
    }

    public ConsolidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
