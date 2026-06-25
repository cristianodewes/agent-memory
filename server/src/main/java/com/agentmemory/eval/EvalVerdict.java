package com.agentmemory.eval;

import java.util.List;

/**
 * The outcome of running (or skipping) the {@link EvalGate} on a proposal (issue #31).
 *
 * <p>Three decisions: {@link Decision#PASSED} (the gate ran and approved), {@link Decision#BLOCKED}
 * (the gate ran and rejected, <em>or</em> the gate could not produce a trustworthy verdict — the
 * fail-closed cases: timeout, non-zero exit, unparseable output, start failure), and
 * {@link Decision#SKIPPED} (the gate did not apply: disabled, no command, or the path matched no
 * configured prefix). Only {@code BLOCKED} stops a proposal; {@code SKIPPED} and {@code PASSED} both
 * {@link #allowed() allow} it.
 *
 * @param decision the gate decision; never null.
 * @param reasons  human-readable reasons (from the gate's {@code reasons} array, or the fail-closed
 *                 cause); never null, may be empty.
 */
public record EvalVerdict(Decision decision, List<String> reasons) {

    /** Whether the gate applied, and if so its verdict. */
    public enum Decision {
        /** The gate ran and approved the proposal. */
        PASSED,
        /** The gate ran and rejected the proposal, or failed closed (timeout/error/malformed). */
        BLOCKED,
        /** The gate did not apply (disabled, no command, or prefix not selected). */
        SKIPPED
    }

    public EvalVerdict {
        if (decision == null) {
            throw new IllegalArgumentException("verdict decision must not be null");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** @return {@code true} unless the proposal was blocked — i.e. it may proceed to staging/approval. */
    public boolean allowed() {
        return decision != Decision.BLOCKED;
    }

    /** @return {@code true} when the proposal was blocked by the gate. */
    public boolean blocked() {
        return decision == Decision.BLOCKED;
    }

    public static EvalVerdict skipped() {
        return new EvalVerdict(Decision.SKIPPED, List.of());
    }

    public static EvalVerdict passed(List<String> reasons) {
        return new EvalVerdict(Decision.PASSED, reasons);
    }

    public static EvalVerdict blocked(List<String> reasons) {
        return new EvalVerdict(Decision.BLOCKED, reasons);
    }

    public static EvalVerdict blocked(String reason) {
        return new EvalVerdict(Decision.BLOCKED, List.of(reason));
    }
}
