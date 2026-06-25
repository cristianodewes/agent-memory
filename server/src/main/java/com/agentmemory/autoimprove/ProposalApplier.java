package com.agentmemory.autoimprove;

import com.agentmemory.recall.Scope;

/**
 * Writes an approved proposal through the normal durable-write path (issue #30: "default applies via the
 * write path"). A seam so the approval gate doesn't depend on the write stack directly: production wires
 * it to the #19/#20 {@code MemoryWriteService}; tests use a fake. Keeping this an interface also lets the
 * gate be exercised without the wiki/git machinery.
 */
@FunctionalInterface
public interface ProposalApplier {

    /**
     * Apply one approved proposal (create/update the page at its path).
     *
     * @param scope the project to write into; never null.
     * @param write the approved proposal; never null.
     * @throws RuntimeException if the write fails (the gate marks the proposal still-pending and records
     *     the error rather than losing it).
     */
    void apply(Scope scope, ProposedWrite write);
}
