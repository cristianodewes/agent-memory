package com.agentmemory.llmrecall;

import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.Scope;
import java.util.List;

/**
 * The do-nothing {@link AccessReinforcer}: recall returns its hits and no access bookkeeping happens.
 * This is the default when no JDBC {@link javax.sql.DataSource} is present (the DB-less smoke context)
 * and the safe fallback in general — reinforcement is an optional enhancement, never a requirement of
 * recall. Stateless and trivially thread-safe.
 */
public final class NoOpAccessReinforcer implements AccessReinforcer {

    @Override
    public void reinforce(Scope scope, List<RecallHit> hits) {
        // Intentionally empty: no decay model is wired, so there is nothing to reinforce.
    }
}
