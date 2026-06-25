package com.agentmemory.consolidate;

import com.agentmemory.hooks.NewObservation;
import com.agentmemory.hooks.ObservationListener;

/**
 * Adapts the ingest layer's {@link ObservationListener} seam (issue #8) to session consolidation
 * (issue #18): on every persisted observation it forwards the session id and kind to the
 * {@link SessionConsolidationTrigger}, which (re)synthesizes the session when the kind is a trigger
 * ({@code session-end}/{@code pre-compact}) and ignores everything else.
 *
 * <p>This is the wiring that actually <em>connects</em> the synthesis trigger to capture — without it
 * the trigger is a seam nothing invokes and session-end synthesis never runs. Keeping the adapter in
 * {@code consolidate} (which depends on {@code hooks}) rather than calling the trigger from
 * {@code IngestService} keeps the foundational ingest module free of any dependency on the LLM-backed
 * consolidate module; ingest only knows the {@link ObservationListener} interface.
 *
 * <p>Best-effort by construction: {@link SessionConsolidationTrigger#onObservation} catches and logs
 * any synthesis failure and returns normally, and {@code IngestService} additionally guards the call,
 * so consolidation can never break the capture path (invariant #5).
 */
public final class ConsolidationObservationListener implements ObservationListener {

    private final SessionConsolidationTrigger trigger;

    public ConsolidationObservationListener(SessionConsolidationTrigger trigger) {
        this.trigger = trigger;
    }

    @Override
    public void onObservationWritten(NewObservation observation) {
        // The typed observation carries the session id and canonical kind the trigger needs; the
        // trigger no-ops for non-triggering kinds, so there is no per-event policy duplicated here.
        trigger.onObservation(observation.sessionId(), observation.kind());
    }
}
