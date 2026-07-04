package com.agentmemory.autoimprove;

import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.util.List;

/**
 * Turns a finished session into proposed durable-knowledge edits (issue #30) — the "review engine" the
 * scheduler runs over each freshly-finished session. Production wires two engines (#150): the #29
 * rule-based curator adapter ({@link CuratorProposalSource}) and the LLM-backed session review
 * ({@link SessionReviewProposalSource}). Tests supply fakes.
 *
 * <p>Keeping the engine behind this interface is what lets the scheduler, the approval gate, and the
 * {@code memory_auto_improve} tool evolve independently of the review machinery.
 *
 * <h2>Per-source claims (#150)</h2>
 * Each source owns its own claim namespace in {@code auto_improve_session_review} (the {@code source}
 * column, V16), keyed by {@link #id()}: the curator marking a session {@code done} must not hide it
 * from the LLM review engine, and vice versa. {@link #id()} defaults to {@code "curator"} — the only
 * engine that existed before #150 — so the pre-existing adapter and every legacy claim keep their
 * meaning without an override.
 *
 * <h2>Tick lifecycle (#150)</h2>
 * {@link #beginTick()} lets a source reset per-tick state (the review engine's LLM-call budget);
 * {@link #ready(int)} is consulted <em>before each claim</em> so a source that cannot usefully run
 * right now (rate-limit gate paused, budget exhausted, outside its quiet-hours window) stops the tick
 * for itself <em>without consuming claims</em> — deferral is not failure; {@link #endTick()} lets a
 * source flush per-tick aggregates (the review engine's report page) through the gate. All three
 * default to no-ops so a plain lambda (tests) stays a valid source.
 */
@FunctionalInterface
public interface ProposalSource {

    /**
     * Review one finished session and return the durable edits it suggests.
     *
     * @param scope   the session's project; never null.
     * @param session the finished session to review; never null.
     * @return the proposed edits (possibly empty); never null.
     */
    List<ProposedWrite> proposalsFor(Scope scope, SessionId session);

    /**
     * This source's claim namespace in {@code auto_improve_session_review.source} (V16). Stable —
     * changing it orphans the source's history. Defaults to {@code "curator"}, the pre-#150 engine.
     */
    default String id() {
        return "curator";
    }

    /** Reset per-tick state (budgets, aggregates). Called once at the start of each tick. */
    default void beginTick() {}

    /**
     * Whether this source can usefully review another session <em>right now</em>. Consulted before
     * each claim; returning {@code false} stops this source's pass for the tick without consuming
     * claims or attempts (the sessions stay due). The review engine returns {@code false} while the
     * shared rate-limit gate is paused, its LLM budget is spent, or it is outside its quiet-hours
     * window with a small backlog.
     *
     * @param backlog how many sessions are currently due for this source (this tick's view).
     */
    default boolean ready(int backlog) {
        return true;
    }

    /**
     * Flush per-tick aggregates as scoped proposals (e.g. the review engine's report page), submitted
     * by the scheduler through the same approval gate with no owning session. Called once at the end
     * of each tick, after the last session.
     *
     * @return scoped writes to submit; never null, empty by default.
     */
    default List<ScopedWrite> endTick() {
        return List.of();
    }

    /** A proposal bound to its project, for tick-level (session-less) writes like the report page. */
    record ScopedWrite(Scope scope, ProposedWrite write) {
        public ScopedWrite {
            if (scope == null || write == null) {
                throw new IllegalArgumentException("scope and write must not be null");
            }
        }
    }
}
