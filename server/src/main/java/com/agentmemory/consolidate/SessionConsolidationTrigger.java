package com.agentmemory.consolidate;

import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.SessionId;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The trigger seam for session consolidation (issue #18): given a just-captured observation's kind,
 * decide whether to (re)synthesize the session and, if so, run it. {@code session-end} and
 * {@code pre-compact} fire synthesis; every other kind is ignored. This keeps the "which events
 * consolidate" policy in {@code consolidate} rather than in the ingest path — the hook/ingest layer
 * (#8) simply calls {@link #onObservation} after persisting an event, mirroring how other write-time
 * side effects are wired as seams.
 *
 * <h2>Best-effort</h2>
 * Consolidation must never break ingest. {@link #onObservation} catches every failure (a malformed
 * LLM reply, a provider outage, a write error) and logs it, returning normally — a failed synthesis
 * is retried on the next triggering event (and the work is idempotent), and the capture log is
 * unaffected. A caller that wants the failure (a manual/CLI invocation, a test) calls
 * {@link SessionSynthesizer#synthesize} directly.
 */
public class SessionConsolidationTrigger {

    private static final Logger log = LoggerFactory.getLogger(SessionConsolidationTrigger.class);

    /** Observation kinds that trigger a session synthesis. */
    static final Set<ObservationKind> TRIGGER_KINDS =
            Set.of(ObservationKind.SESSION_END, ObservationKind.PRE_COMPACT);

    private final SessionSynthesizer synthesizer;

    public SessionConsolidationTrigger(SessionSynthesizer synthesizer) {
        this.synthesizer = synthesizer;
    }

    /** @return {@code true} if an observation of this kind triggers consolidation. */
    public static boolean triggers(ObservationKind kind) {
        return TRIGGER_KINDS.contains(kind);
    }

    /**
     * React to a just-captured observation. If its {@code kind} is a trigger
     * ({@code session-end}/{@code pre-compact}), (re)synthesize the session best-effort. Any failure
     * is logged and swallowed so consolidation never breaks the capture path.
     *
     * @param sessionId the session the observation belongs to; never null.
     * @param kind      the captured observation's kind; never null.
     * @return the outcome when synthesis ran, or {@code null} when the kind did not trigger or
     *     synthesis failed (a failure is logged; it is retried on the next trigger).
     */
    public SynthesisOutcome onObservation(SessionId sessionId, ObservationKind kind) {
        if (sessionId == null || kind == null) {
            throw new IllegalArgumentException("sessionId and kind must not be null");
        }
        if (!triggers(kind)) {
            return null;
        }
        try {
            return synthesizer.synthesize(sessionId);
        } catch (RuntimeException e) {
            // DD-005: synthesis is LLM-driven with no fallback, but it must not break ingest. Log and
            // move on; the next session-end/pre-compact retries, and synthesis is idempotent.
            log.warn("session {} consolidation failed on {} (will retry on next trigger): {}",
                    sessionId, kind.wire(), e.getMessage());
            return null;
        }
    }
}
