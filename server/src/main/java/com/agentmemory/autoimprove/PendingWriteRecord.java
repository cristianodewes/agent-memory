package com.agentmemory.autoimprove;

import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code pending_writes} (V8) as read back for the report / queries (issue #30): the proposal,
 * its gate status, the optional eval-gate result, and the lifecycle timestamps.
 *
 * @param id         surrogate id.
 * @param workspace  workspace slug.
 * @param project    project slug.
 * @param path       target page path, or null for a project-scoped proposal.
 * @param status     approval-gate status.
 * @param kind       change kind (e.g. {@code page.edit}).
 * @param proposal   the structured proposed change (JSON text).
 * @param rationale  why it was proposed, or null.
 * @param evalResult the optional eval-gate (#31) outcome (JSON text), or null when not run.
 * @param sessionId  the session the proposal came from, or null.
 * @param createdAt  when proposed.
 * @param decidedAt  when approved/rejected, or null.
 * @param appliedAt  when applied, or null.
 */
public record PendingWriteRecord(
        UUID id,
        String workspace,
        String project,
        String path,
        PendingWriteStatus status,
        String kind,
        String proposal,
        String rationale,
        String evalResult,
        UUID sessionId,
        Instant createdAt,
        Instant decidedAt,
        Instant appliedAt) {}
