package com.agentmemory.consolidate;

import com.agentmemory.core.SessionId;
import com.agentmemory.store.PageRecord;
import java.util.List;

/**
 * The result of a {@link Consolidator#consolidate} call (issue #19): either the durable pages that
 * were written, or a no-op because the session had no observations to consolidate.
 *
 * @param sessionId the session consolidated; never null.
 * @param status    what happened.
 * @param pages     the written page versions (empty unless {@code status == WRITTEN}); never null.
 */
public record ConsolidationOutcome(SessionId sessionId, Status status, List<PageRecord> pages) {

    /** The disposition of a consolidation. */
    public enum Status {
        /** Durable pages were written (see {@link #pages()}). */
        WRITTEN,
        /** The session had no observations; nothing was written. */
        NO_OBSERVATIONS
    }

    public ConsolidationOutcome {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** A successful consolidation that wrote {@code pages}. */
    public static ConsolidationOutcome written(SessionId sessionId, List<PageRecord> pages) {
        return new ConsolidationOutcome(sessionId, Status.WRITTEN, pages);
    }

    /** A no-op: the session had no observations to consolidate. */
    public static ConsolidationOutcome noObservations(SessionId sessionId) {
        return new ConsolidationOutcome(sessionId, Status.NO_OBSERVATIONS, List.of());
    }

    /** @return whether durable pages were written. */
    public boolean wrote() {
        return status == Status.WRITTEN;
    }
}
