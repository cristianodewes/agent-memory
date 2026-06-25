package com.agentmemory.consolidate;

import com.agentmemory.core.Observation;
import com.agentmemory.core.Session;
import com.agentmemory.core.SessionId;
import java.util.List;
import java.util.Optional;

/**
 * Reads a session and its captured observations for consolidation (issue #18). The capture log is
 * written by the single writer in {@code store} (#8); this is the read side consolidation needs and
 * the only place #18 touches the {@code sessions}/{@code observations} tables.
 *
 * <p>The observations returned are already <strong>sanitized</strong>: the {@code payload} column was
 * privacy-stripped at write time (DD-010 / invariant #6 — only a {@code Sanitized} value can reach the
 * writer), so what is read back is safe to feed to the LLM. No re-sanitization happens here.
 */
public interface SessionObservationReader {

    /**
     * Load the session row.
     *
     * @param sessionId the session id; never null.
     * @return the session, or empty if no such session exists.
     */
    Optional<Session> findSession(SessionId sessionId);

    /**
     * Load all observations for a session in capture order (by {@code created_at}, then id for a
     * stable tie-break), so the synthesized narrative follows the run's timeline. Payloads are
     * sanitized (see the class note).
     *
     * @param sessionId the session id; never null.
     * @return the session's observations, oldest first (possibly empty).
     */
    List<Observation> observationsFor(SessionId sessionId);

    /**
     * Count observations for a session — a cheap freshness signal used for idempotency (re-running a
     * session whose observation count is unchanged need not re-synthesize).
     *
     * @param sessionId the session id; never null.
     * @return the number of observations recorded under the session.
     */
    int countFor(SessionId sessionId);
}
