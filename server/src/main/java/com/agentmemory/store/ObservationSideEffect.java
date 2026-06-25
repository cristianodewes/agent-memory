package com.agentmemory.store;

import com.agentmemory.core.Observation;

/**
 * Extension point for #11 (session ledger + raw archive) to attach the {@code log.md} append and the
 * immutable {@code raw/} write to the <em>same logical operation</em> as the observation-row insert
 * (mirrors {@link PageWriteCallback} on the page side; DD-002, invariants #3 and #10).
 *
 * <p>The callback runs <strong>inside {@link ObservationWriter#append}'s write transaction</strong>,
 * on the single-writer thread and under its lock, <em>after</em> a genuinely-new observation row has
 * been inserted but <em>before</em> the transaction commits. It is invoked exactly once per real
 * insert and <strong>not</strong> on an idempotent replay (so the ledger/archive never duplicate).
 * If it throws, the whole operation rolls back — the row, the ledger line and the raw entry are one
 * unit, so the DB index can never claim an event whose file side effects failed.
 *
 * <p>A no-op is expressed by passing {@code null} (the DB-less smoke configuration and pure unit
 * tests do this) or by a lambda; this keeps the writer usable without the wiki layer present.
 */
@FunctionalInterface
public interface ObservationSideEffect {

    /**
     * Invoked within the write transaction with the just-inserted observation (server-assigned id,
     * project-scoped identity, sanitized payload).
     *
     * @param persisted the freshly-stored observation row.
     * @throws Exception to abort and roll back the entire write (row + side effect together).
     */
    void afterObservationWritten(Observation persisted) throws Exception;
}
