package com.agentmemory.store;

import com.agentmemory.core.Identity;

/**
 * Appends rows to the {@code audit_log} table (V8) — every mutation, addressable by {@code at DESC}
 * (ARCHITECTURE §4.2). Until now audit writes were inline in the observation writer (#8); lifecycle
 * ops (#33) need to record rename/move/purge/reset with their <em>before</em> and <em>after</em>
 * identity, so this is the shared, typed seam. {@code audit_log} is primary state (backup/restore,
 * #34), never rebuilt by reindex.
 */
public interface AuditWriter {

    /**
     * Record one mutation. The {@code subject} identity supplies the {@code (workspace, project, path)}
     * the row is filed under (path null for a project-scoped op); {@code detail} is free-form structured
     * context already serialized as a JSON object string (e.g. the before/after identity of a rename).
     *
     * @param subject    the identity the mutation is filed under (project- or page-scoped); never null.
     * @param action     the action key, e.g. {@code "project.rename"} / {@code "project.purge"}; never null.
     * @param entityType the affected entity type, e.g. {@code "project"}, or {@code null}.
     * @param detailJson a JSON object string for {@code detail} (e.g. {@code {"from":...}}); {@code null}
     *                   ⇒ {@code {}}.
     */
    void record(Identity subject, String action, String entityType, String detailJson);
}
