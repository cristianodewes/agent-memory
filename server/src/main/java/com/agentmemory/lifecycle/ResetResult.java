package com.agentmemory.lifecycle;

/**
 * Outcome of a {@code reset} (issue #33): whether it ran and, if refused, why. {@code reset} wipes the
 * whole index + wiki, so it is guarded by the live-process check (invariant #9).
 *
 * @param performed   {@code true} if the reset wiped state; {@code false} if refused.
 * @param reason      a short human explanation (the refusal cause, or {@code "ok"} when performed).
 * @param liveHolderPid the PID of the live process that blocked the reset, or {@code -1} when none.
 * @param tablesCleared how many DB tables were truncated (0 when refused).
 */
public record ResetResult(boolean performed, String reason, long liveHolderPid, int tablesCleared) {

    public ResetResult {
        if (reason == null) {
            reason = "";
        }
    }

    /** A refusal because a live process holds the data dir. */
    public static ResetResult refusedLiveProcess(long pid) {
        return new ResetResult(false,
                "refused: a live agent-memory process (pid " + pid + ") is using this data dir; "
                        + "stop it before resetting (invariant #9)",
                pid, 0);
    }

    /** A successful reset. */
    public static ResetResult ok(int tablesCleared) {
        return new ResetResult(true, "ok", -1L, tablesCleared);
    }
}
