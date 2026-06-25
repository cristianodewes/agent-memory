package com.agentmemory.consolidate;

import com.agentmemory.core.SessionId;
import com.agentmemory.store.PageRecord;

/**
 * The result of a {@link SessionSynthesizer#synthesize} call: whether a page was written, the call
 * was a no-op (already synthesized / idempotent re-run), or the session had nothing to compile.
 * Carries the resulting {@link PageRecord} when one is relevant so the caller (and tests) can assert
 * on it.
 *
 * @param sessionId the session this outcome is for.
 * @param status    what happened.
 * @param page      the session page (written or pre-existing), or {@code null} for NO_OBSERVATIONS.
 * @param synthesis the synthesis content when freshly WRITTEN, else {@code null}.
 */
public record SynthesisOutcome(
        SessionId sessionId, Status status, PageRecord page, SynthesizedSession synthesis) {

    /** What a synthesis attempt did. */
    public enum Status {
        /** A new session page version was written (and committed). */
        WRITTEN,
        /** Idempotent no-op: the latest page already reflects the current observations. */
        SKIPPED,
        /** The session had no observations; nothing was written. */
        NO_OBSERVATIONS
    }

    static SynthesisOutcome written(SessionId id, PageRecord page, SynthesizedSession synthesis) {
        return new SynthesisOutcome(id, Status.WRITTEN, page, synthesis);
    }

    static SynthesisOutcome skipped(SessionId id, PageRecord existing) {
        return new SynthesisOutcome(id, Status.SKIPPED, existing, null);
    }

    static SynthesisOutcome noObservations(SessionId id) {
        return new SynthesisOutcome(id, Status.NO_OBSERVATIONS, null, null);
    }

    /** @return {@code true} when a new page version was written. */
    public boolean wasWritten() {
        return status == Status.WRITTEN;
    }
}
