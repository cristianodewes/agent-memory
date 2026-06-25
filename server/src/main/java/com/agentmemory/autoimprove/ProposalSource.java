package com.agentmemory.autoimprove;

import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.util.List;

/**
 * Turns a finished session into proposed durable-knowledge edits (issue #30) — the "review engine" the
 * scheduler runs over each freshly-finished session. A seam, deliberately the only coupling point to the
 * actual review machinery: production wires it to the #29 curator and/or #19 consolidation (mapped onto
 * {@link ProposedWrite}); this PR ships <strong>no production binding</strong>, so the scheduler is inert
 * until those engines land (it logs and skips when no source is wired). Tests supply a fake.
 *
 * <p>Keeping the engine behind this interface is what lets the scheduler, the approval gate, and the
 * {@code memory_auto_improve} tool be built and tested now without pulling the curator/LLM stack into the
 * loop, and without adding a production call-site that fires before the engines are ready.
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
