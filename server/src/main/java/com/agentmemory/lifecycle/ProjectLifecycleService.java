package com.agentmemory.lifecycle;

import com.agentmemory.core.Identity;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;

/**
 * Project lifecycle operations (issue #33; ARCHITECTURE design goal #6 "projects cheap to rename /
 * move / purge"). Because identity is a typed 3-tuple and projects are isolated by construction, these
 * are cheap and <strong>sibling-safe</strong>: only the named project's rows and wiki subtree change.
 *
 * <p>Each op updates the Postgres index <em>and</em> the wiki subtree together (DB transaction; the
 * filesystem move/delete is sequenced inside it so a failure rolls the DB back), re-points the project's
 * cross-project {@code links}, and writes an {@code audit_log} row carrying the before/after identity.
 */
public interface ProjectLifecycleService {

    /**
     * Rename a project within its workspace ({@code (ws, old)} → {@code (ws, new)}). Updates the
     * project row and every denormalized {@code project} slug ({@code pages}, {@code sessions},
     * {@code observations}, {@code audit_log}, {@code links}), moves {@code wiki/<ws>/<old>} →
     * {@code wiki/<ws>/<new>} (git-committed), and re-points links.
     *
     * @param project the source project-scoped identity (path must be null); never null.
     * @param newName the new project slug; never null.
     * @return the op result with before/after identity and counts.
     * @throws LifecycleException if the source project does not exist or {@code (ws, new)} already
     *                            exists (no silent overwrite of a sibling).
     */
    ProjectOpResult renameProject(Identity project, ProjectId newName);

    /**
     * Move a project to another workspace (and optionally rename it): {@code (oldWs, oldProj)} →
     * {@code (newWs, newProj)}. The target workspace is created on demand. Updates the FK and every
     * denormalized {@code (workspace, project)} slug, moves the wiki subtree, and re-points links.
     *
     * @param project the source project-scoped identity (path must be null); never null.
     * @param newWorkspace the destination workspace; never null.
     * @param newName the destination project slug (use the same value to keep the name); never null.
     * @return the op result with before/after identity and counts.
     * @throws LifecycleException if the source does not exist or the destination already exists.
     */
    ProjectOpResult moveProject(Identity project, WorkspaceId newWorkspace, ProjectId newName);

    /**
     * Purge a project: delete its wiki subtree and all its DB rows (pages → cascade embeddings + links,
     * sessions → cascade observations) and the project row itself. Sibling projects and the workspace
     * are untouched. <strong>Idempotent</strong>: purging a project that does not exist is a no-op
     * (reported with {@code pagesAffected == 0}).
     *
     * @param project the project-scoped identity to purge (path must be null); never null.
     * @return the op result (before == after == the purged identity).
     */
    ProjectOpResult purgeProject(Identity project);
}
