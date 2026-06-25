package com.agentmemory.store;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PageId;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.util.List;
import java.util.Optional;

/**
 * Versioned storage for wiki pages — the DB-side index of the {@code pages} table (ARCHITECTURE
 * §4.2, issue #12). Pages are versioned in place via an {@code is_latest} + {@code supersedes} chain:
 * a new version is the current one and points back at the version it replaced; superseded rows are
 * kept (history is preserved — never hard-deleted here) but are invisible to "latest" reads.
 *
 * <p>All writes go through the single-writer discipline (DD-006): {@link #create} is atomic (the new
 * row, the prior row's {@code is_latest} flip, and the generated FTS column all commit in one
 * transaction — invariants #3/#10) and concurrent writes to the <em>same</em> page path are
 * serialized (acceptance criterion: no lost updates). Page <em>body</em> authority is deferred to
 * the wiki layer (#13) per DD-002; this interface owns only the relational index. #13 attaches its
 * markdown write to the same logical operation via {@link PageWriteCallback}.
 */
public interface PageRepository {

    /**
     * Create a new version of the page at {@code identity}. If a current version already exists at
     * that {@code (workspace, project, path)}, it is superseded: its {@code is_latest} flips to
     * {@code false} and the new row's {@code supersedes} points at it. The first version for a path
     * has a {@code null} {@code supersedes}. The parent workspace/project rows are created on demand.
     *
     * <p>Atomic and serialized per path. The generated {@code tsvector} FTS column is populated by
     * the same {@code INSERT} (no post-commit indexing). Access columns start at {@code 0}/{@code null}.
     *
     * @param identity page-scoped identity (path required); never null.
     * @param title    page title; never null.
     * @param body     markdown body; never null (may be empty).
     * @return the newly stored {@code is_latest} version.
     */
    PageRecord create(Identity identity, String title, String body);

    /**
     * Like {@link #create(Identity, String, String)} but runs {@code callback} inside the same write
     * transaction, after the row is written and before commit — the #13 hook for attaching the
     * atomic markdown/git write to the same logical operation. A {@code null} callback is a no-op.
     * If the callback throws, the entire write (row + side effect) rolls back.
     *
     * @param identity page-scoped identity (path required); never null.
     * @param title    page title; never null.
     * @param body     markdown body; never null (may be empty).
     * @param callback in-transaction side effect, or {@code null} for none.
     * @return the newly stored {@code is_latest} version.
     */
    PageRecord create(Identity identity, String title, String body, PageWriteCallback callback);

    /**
     * Read the current version of the page at {@code identity}. Never returns a superseded row.
     *
     * @param identity page-scoped identity (path required); never null.
     * @return the latest version, or empty if no page exists at that path.
     */
    Optional<PageRecord> readLatest(Identity identity);

    /**
     * List the current version of every page in a project, most-recently-updated first. Never
     * includes superseded rows.
     *
     * @param workspace the workspace coordinate; never null.
     * @param project   the project coordinate; never null.
     * @return the latest pages in {@code (workspace, project)} (possibly empty).
     */
    List<PageRecord> listLatest(WorkspaceId workspace, ProjectId project);

    /**
     * Fetch one specific page <em>version</em> by its surrogate id (any point in a chain, latest or
     * superseded). Useful for walking history and for tests.
     *
     * @param id the page-version id; never null.
     * @return that version, or empty if no such row exists.
     */
    Optional<PageRecord> findById(PageId id);

    /**
     * Apply a decay-reinforcement bump to one page version: increment {@code access_count} and set
     * {@code last_accessed_at} to now (ARCHITECTURE §3.3, issue #24). Recall calls this for each hit
     * it returns, lifting the page's retention score so used knowledge survives the forget sweep
     * (#25). Atomic; returns the post-bump record so the caller sees its own reinforcement.
     *
     * @param id the page-version id to reinforce; never null.
     * @return the updated record, or empty if no row has that id (nothing to reinforce).
     */
    Optional<PageRecord> reinforce(PageId id);

    /**
     * Drop every {@link com.agentmemory.core.MemoryLayer#WORKING working}-layer latest page in a
     * project from "latest" — the session-end demotion of volatile scratch (issue #24 acceptance:
     * working memory is hard-dropped from latest at session end but kept as observations/history).
     * The rows are <em>not</em> deleted; their {@code is_latest} flips to {@code false} so they stop
     * surfacing in recall and listings while the underlying knowledge persists as raw observations.
     * Pages in other layers are untouched.
     *
     * @param workspace the workspace coordinate; never null.
     * @param project   the project coordinate; never null.
     * @return the number of working-layer pages demoted.
     */
    int dropWorkingFromLatest(WorkspaceId workspace, ProjectId project);
}
