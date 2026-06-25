package com.agentmemory.store;

import com.agentmemory.core.Handoff;
import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.util.List;
import java.util.Optional;

/**
 * Versioned storage for typed handoffs — the relational side of the {@code handoffs} table
 * (ARCHITECTURE §3.4, §4.2; issue #22). A handoff is <strong>single-use</strong>
 * ({@code open → accepted → expired}) and there is <strong>at most one open handoff per project</strong>
 * (the {@code handoffs_one_open_per_project} partial unique index).
 *
 * <p>All mutations run under the single-writer discipline (DD-006): {@link #open} supersedes any
 * existing open handoff (expires it) and inserts the new one in one transaction, serialized per
 * project so two concurrent opens cannot both win; {@link #acceptLatestOpen} flips the open handoff
 * to {@code accepted} atomically so a second accept returns empty (single-use). Bodies are written
 * by the LLM-backed {@code HandoffService}; this interface owns only persistence.
 */
public interface HandoffRepository {

    /**
     * Open a new handoff for {@code scope}, superseding any currently-open one (which is expired
     * first so the {@code one open per project} invariant holds). The new handoff is {@code open}
     * with {@code accepted_at = null}.
     *
     * @param scope         the project the handoff belongs to; never null.
     * @param fromSession   the session it summarizes; never null.
     * @param summary       prose "where you left off"; never null.
     * @param openQuestions unresolved questions; never null (may be empty).
     * @param nextSteps     suggested next actions; never null (may be empty).
     * @return the newly opened handoff.
     */
    Handoff open(Scope scope, SessionId fromSession, String summary,
            List<String> openQuestions, List<String> nextSteps);

    /**
     * Accept (consume) the latest open handoff for {@code scope}: flip it to {@code accepted} and
     * stamp {@code accepted_at}. Single-use — once accepted it is never returned again, so a second
     * call returns {@link Optional#empty()}.
     *
     * @param scope the project; never null.
     * @return the now-accepted handoff, or empty when there is no open handoff to accept.
     */
    Optional<Handoff> acceptLatestOpen(Scope scope);

    /**
     * Cancel (expire) the latest open handoff for {@code scope} without consuming it — for a mistaken
     * handoff. Returns the expired handoff, or empty when there is none open.
     *
     * @param scope the project; never null.
     * @return the now-expired handoff, or empty.
     */
    Optional<Handoff> cancelLatestOpen(Scope scope);

    /**
     * Read the latest open handoff for {@code scope} without consuming it (a peek).
     *
     * @param scope the project; never null.
     * @return the open handoff, or empty.
     */
    Optional<Handoff> findLatestOpen(Scope scope);

    /**
     * Fetch a handoff by id (any state), for endpoints and tests.
     *
     * @param id the handoff id; never null.
     * @return that handoff, or empty.
     */
    Optional<Handoff> findById(com.agentmemory.core.HandoffId id);

    /**
     * The session's observations (oldest first) — the raw material the LLM reads to write a handoff
     * (ARCHITECTURE §3.4). Returned as lightweight rows, not full domain {@code Observation}s, since
     * the synthesizer only needs the kind + text + time to ground its prompt.
     *
     * @param session the session; never null.
     * @param limit   max observations to return (the most recent {@code limit}, in chronological order).
     * @return the session's observations, oldest first; possibly empty.
     */
    List<ObservationLine> sessionObservations(SessionId session, int limit);

    /**
     * One observation row as the handoff synthesizer reads it: the canonical kind, the sanitized
     * captured text, and when it happened.
     *
     * @param kind      the canonical observation kind wire token.
     * @param payload   the sanitized captured text.
     * @param createdAt RFC-3339 instant string of when the event occurred.
     */
    record ObservationLine(String kind, String payload, String createdAt) {}
}
