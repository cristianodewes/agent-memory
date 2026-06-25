package com.agentmemory.autoimprove;

import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.util.List;

/**
 * Turns a finished session into proposed durable-knowledge edits (issue #30) — the "review engine" the
 * scheduler runs over each freshly-finished session. A seam, deliberately the only coupling point to the
 * actual review machinery: production binds it to the #19 consolidation in propose-only mode
 * ({@link ConsolidationProposalSource}), which maps each distilled page onto a {@link ProposedWrite}; a
 * future action-shaped source over the #29 curator (forget/merge/fix) is tracked separately (#101). With no
 * source wired (e.g. a DB-less / no-LLM context) the scheduler logs and skips. Tests supply a fake.
 *
 * <p>Keeping the engine behind this interface is what lets the scheduler, the approval gate, and the
 * {@code memory_auto_improve} tool stay independent of the consolidation/LLM stack and be unit-tested with a
 * fake source.
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
}
