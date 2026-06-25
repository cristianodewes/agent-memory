package com.agentmemory.lifecycle;

/**
 * The destructive {@code reset} (issue #33): wipe the entire Postgres index <em>and</em> the wiki
 * contents back to empty. Because it destroys everything, it is guarded by the live-process check
 * (invariant #9): it refuses to run while a live agent-memory process holds the data dir, and only
 * proceeds when that is safe (or explicitly forced).
 */
public interface ResetService {

    /**
     * Wipe all state.
     *
     * @param force when {@code false}, refuse if a live process holds the data dir; when {@code true},
     *              proceed regardless (the operator has accepted the risk).
     * @return the outcome — performed with a table count, or refused with the blocking PID.
     */
    ResetResult reset(boolean force);
}
