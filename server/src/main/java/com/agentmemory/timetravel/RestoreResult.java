package com.agentmemory.timetravel;

import java.util.Map;

/**
 * The outcome of a {@code restore} from a backup tarball (issue #34). A restore is destructive (it
 * truncates the primary tables before reloading), so it is guarded by the live-process check
 * (invariant #9): when a live holder is present and {@code --force} was not given, the restore is
 * refused and {@link #performed()} is {@code false}.
 *
 * @param performed     whether the restore ran (false ⇒ refused by the live-process guard).
 * @param reason        a human-readable explanation (why it was refused, or a success note).
 * @param liveHolderPid the PID of the live data-dir holder that blocked the restore, or {@code null}.
 * @param rowCounts     rows reloaded per table when performed (empty when refused).
 * @param totalRows     the sum across all tables (0 when refused).
 */
public record RestoreResult(boolean performed, String reason, Long liveHolderPid,
                            Map<String, Integer> rowCounts, int totalRows) {

    static RestoreResult refused(long liveHolderPid) {
        return new RestoreResult(false,
                "a live agent-memory process (pid " + liveHolderPid + ") holds the data dir; "
                        + "restore refused (invariant #9). Stop it or pass --force.",
                liveHolderPid, Map.of(), 0);
    }

    static RestoreResult done(Map<String, Integer> rowCounts, int totalRows) {
        return new RestoreResult(true, "restore complete", null, rowCounts, totalRows);
    }
}
