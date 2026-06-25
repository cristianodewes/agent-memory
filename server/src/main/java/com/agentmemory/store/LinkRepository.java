package com.agentmemory.store;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Link;
import com.agentmemory.core.PageId;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;

/**
 * Writer/maintenance side of the {@code links} table (ARCHITECTURE §4.2; V5). The graph-neighborhood
 * recall arm ({@code recall.RecallRepository#graphNeighbors}) reads this table; reindex (#14) is its
 * first writer, rebuilding the wikilink graph from page bodies. Consolidation (#27) reuses the same
 * seam to record links as it compiles pages.
 *
 * <p>Links are <strong>deferred-safe</strong> ({@code core.Link}, V5): a link may name a target that
 * does not exist yet — it is stored unresolved ({@code to_page_id} null, {@code target_resolved}
 * false) and later resolved by {@link #resolveDeferred()} once the target's latest page version
 * exists. Targets may be cross-project; nothing constrains a target's {@code (workspace, project)} to
 * match the source's.
 */
public interface LinkRepository {

    /**
     * Insert one link from {@code fromPageId} (the linking page <em>version</em>). The target page id
     * is resolved here against the current latest version of {@code link.target}: if that page exists
     * the row is written resolved ({@code to_page_id} set, {@code target_resolved} true); otherwise it
     * is written deferred and {@link #resolveDeferred()} can complete it later.
     *
     * @param fromPageId the linking page version's id; never null.
     * @param link       the link to record (its {@code source} must equal {@code fromPageId}'s
     *                   identity; its {@code target} is page-scoped, possibly cross-project); never null.
     */
    void insert(PageId fromPageId, Link link);

    /**
     * Delete every link whose source is the given page version — used by incremental reindex before
     * re-extracting a changed page's links, so a removed wikilink leaves no stale edge.
     *
     * @param fromPageId the source page version id; never null.
     * @return the number of link rows deleted.
     */
    int deleteLinksFrom(PageId fromPageId);

    /**
     * Resolve every currently-deferred link whose target now has a latest page version: fill its
     * {@code to_page_id} and flip {@code target_resolved} true. Idempotent — links already resolved or
     * still missing a target are left untouched. Run after a (re)index batch so forward links written
     * before their targets existed become live graph edges.
     *
     * @return the number of links newly resolved.
     */
    int resolveDeferred();

    /**
     * Re-point every link whose <em>target</em> is the given page identity at that page's current
     * latest version: set {@code to_page_id} to the latest version and {@code target_resolved} true.
     * Used by incremental reindex after a target page gets a new version (its id changed), so inbound
     * links that resolved to the now-superseded version follow the page. Idempotent.
     *
     * @param target the page-scoped target identity whose inbound links should be re-pointed; never
     *               null.
     * @return the number of link rows re-pointed.
     */
    int reresolveTarget(Identity target);

    /**
     * Remove all links — the full-rebuild reset (the {@code links} graph is fully derived from page
     * bodies, DD-002). Capture tables are never touched here. Cheaper and simpler than per-page diffs
     * when rebuilding the whole index from scratch.
     *
     * @return the number of link rows removed.
     */
    int truncateAll();

    /**
     * Count the links currently sourced from the given page version (test/diagnostic helper).
     *
     * @param source the source page-scoped identity; never null.
     * @return the number of link rows whose source identity equals {@code source}.
     */
    long countFrom(Identity source);

    /**
     * Re-point the denormalized {@code (workspace, project)} slugs of every link that references a
     * project being renamed or moved (issue #33). Both link endpoints are updated: rows whose
     * <em>source</em> is in {@code (oldWs, oldProj)} get their {@code source_workspace}/
     * {@code source_project} rewritten, and rows whose <em>target</em> is in {@code (oldWs, oldProj)}
     * (cross-project links pointing at the renamed project) get {@code target_workspace}/
     * {@code target_project} rewritten — so links survive the identity change.
     *
     * <p>Only the denormalized slug columns change; {@code from_page_id}/{@code to_page_id} (and thus
     * {@code target_resolved}) are unaffected because the page-version rows keep their surrogate ids
     * across a rename/move. Idempotent: re-running with the same new identity is a no-op.
     *
     * @param oldWs   the project's current workspace; never null.
     * @param oldProj the project's current slug; never null.
     * @param newWs   the project's new workspace (same as old for a pure rename); never null.
     * @param newProj the project's new slug (same as old for a pure move); never null.
     * @return the number of link rows updated (source + target rewrites).
     */
    int repointProject(WorkspaceId oldWs, ProjectId oldProj, WorkspaceId newWs, ProjectId newProj);
}
