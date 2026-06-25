package com.agentmemory.links;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Uuid7;
import com.agentmemory.store.PageRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the {@code links} table for scoped wikilinks with deferred-safe resolution and automatic
 * backlinks (issue #27; ARCHITECTURE §3.3, §4.2). The two operations that keep the link graph correct
 * on <strong>every page write</strong> both run inside the caller's write transaction via
 * {@link #syncPageLinks(PageRecord)}:
 *
 * <ol>
 *   <li><strong>Outgoing links</strong>: the source page's existing links (keyed by its identity, so
 *       a re-consolidated new version cleanly replaces the prior version's links) are deleted and the
 *       links parsed from the new body are inserted. Each target is resolved to the current page id if
 *       it exists; otherwise it is stored <em>deferred</em> ({@code to_page_id = NULL},
 *       {@code target_resolved = false}) — never dropped.</li>
 *   <li><strong>Inbound re-point</strong>: every link anywhere whose recorded target identity matches
 *       the page just written is (re-)pointed at this version and marked resolved. This is what makes
 *       forward links self-heal: a link written before its target existed resolves the moment the
 *       target page is created — including across projects and workspaces. It also keeps existing
 *       backlinks pointed at the <em>latest</em> version after a re-consolidation.</li>
 * </ol>
 *
 * <p>Because it operates through the shared {@link JdbcTemplate} and is {@code @Transactional}, a
 * caller (the consolidation / page-write flow, or #14 reindex) gets link maintenance committed
 * atomically with the page row. The parse step is delegated to the pure {@link WikiLinkParser}.
 *
 * <p>This is the <strong>single</strong> writer/maintenance authority for the {@code links} table:
 * the canonical scoped-wikilink grammar ({@link WikiLinkParser}) and the deferred-safe resolution
 * live here, and reindex (#14, {@code reindex.ReindexTxn}) drives the same logic — {@link #syncPageLinks}
 * per (re)indexed page, {@link #deleteAllLinks()} for a full-rebuild reset, and
 * {@link #resolveAllDeferred()} for the trailing forward-link pass — so a page's links are identical
 * however they were produced (a write, a consolidation, or a rebuild).
 */
public class WikiLinkService {

    private final JdbcTemplate jdbc;
    private final WikiLinkParser parser;

    public WikiLinkService(JdbcTemplate jdbc, WikiLinkParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    /**
     * Maintain the link graph for a freshly written page: rebuild its outgoing links from the body
     * and re-point any inbound links (deferred or stale) that target this page. Idempotent for a
     * given (page version, body).
     *
     * @param page the page version just persisted (its {@code id} is the current {@code is_latest}).
     * @return the number of outgoing links (re)written for this page.
     */
    @Transactional
    public int syncPageLinks(PageRecord page) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        Identity source = page.identity();
        UUID fromPageId = page.id().value();

        int written = rebuildOutgoing(source, fromPageId, page.page().body());
        resolveInboundTo(source, fromPageId);
        return written;
    }

    /**
     * Remove every link — the full-rebuild reset (the {@code links} graph is fully derived from page
     * bodies, DD-002). Used by reindex {@code FULL} before recreating pages; capture tables untouched.
     *
     * @return the number of link rows removed.
     */
    @Transactional
    public int deleteAllLinks() {
        return jdbc.update("DELETE FROM links");
    }

    /**
     * Resolve every still-deferred link whose target now has a latest page version: fill its
     * {@code to_page_id} and flip {@code target_resolved} true. Idempotent (already-resolved or
     * still-missing links are skipped). Run after a (re)index batch so forward links written before
     * their targets existed become live graph edges. This is the set-based complement to the
     * per-page {@link #resolveInboundTo} re-point that {@link #syncPageLinks} already does.
     *
     * @return the number of links newly resolved.
     */
    @Transactional
    public int resolveAllDeferred() {
        // For every unresolved link whose target now has a latest page version, fill to_page_id and
        // flip target_resolved. The join picks that latest version; the guards keep it idempotent.
        return jdbc.update(
                "UPDATE links l SET to_page_id = p.id, target_resolved = true "
                        + "FROM pages p "
                        + "WHERE NOT l.target_resolved AND l.target_path IS NOT NULL "
                        + "  AND p.is_latest AND p.workspace = l.target_workspace "
                        + "  AND p.project = l.target_project AND p.path = l.target_path");
    }

    /**
     * Delete the source's old links (by identity) and insert the ones parsed from {@code body}.
     *
     * @return the number of links inserted.
     */
    private int rebuildOutgoing(Identity source, UUID fromPageId, String body) {
        String ws = source.workspace().value();
        String proj = source.project().value();
        String path = source.page().value();

        // Replace by identity (not from_page_id) so a re-consolidated version supersedes the prior
        // version's links rather than accumulating duplicates across versions.
        jdbc.update(
                "DELETE FROM links WHERE source_workspace = ? AND source_project = ? AND source_path = ?",
                ws, proj, path);

        List<WikiLink> links = parser.parse(source, body);
        for (WikiLink link : links) {
            Identity target = link.target();
            String tws = target.workspace().value();
            String tproj = target.project().value();
            String tpath = target.page().value();

            // Resolve to the current latest version of the target, if it exists (deferred otherwise).
            UUID toPageId = jdbc.query(
                    "SELECT id FROM pages WHERE workspace = ? AND project = ? AND path = ? AND is_latest",
                    rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                    tws, tproj, tpath);

            jdbc.update(
                    "INSERT INTO links (id, from_page_id, source_workspace, source_project, source_path, "
                            + "to_page_id, target_workspace, target_project, target_path, anchor, "
                            + "target_resolved) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Uuid7.randomUuid(), fromPageId, ws, proj, path,
                    toPageId, tws, tproj, tpath, link.anchor(), toPageId != null);
        }
        return links.size();
    }

    /**
     * Point every link whose recorded target identity is this page at this version, marking it
     * resolved. Resolves deferred forward links the moment their target appears, and re-points
     * existing backlinks to the latest version after a re-consolidation.
     */
    private void resolveInboundTo(Identity target, UUID toPageId) {
        jdbc.update(
                "UPDATE links SET to_page_id = ?, target_resolved = true "
                        + "WHERE target_workspace = ? AND target_project = ? AND target_path = ? "
                        + "AND (to_page_id IS DISTINCT FROM ? OR NOT target_resolved)",
                toPageId,
                target.workspace().value(), target.project().value(), target.page().value(),
                toPageId);
    }

    // --- reads -------------------------------------------------------------------------------------

    /**
     * Backlinks of a page: the pages whose body links <em>to</em> it (resolved links only), each
     * carrying its origin {@code (workspace, project)} so cross-project backlinks are visible.
     *
     * @param target the page whose backlinks to list; page-scoped.
     * @return the linking pages, newest link first.
     */
    @Transactional(readOnly = true)
    public List<RelatedPage> backlinksOf(Identity target) {
        requirePageScoped(target);
        String ws = target.workspace().value();
        String proj = target.project().value();
        String path = target.page().value();
        return jdbc.query(
                "SELECT l.source_workspace AS w, l.source_project AS p, l.source_path AS path, "
                        + "       sp.title AS title, l.anchor AS anchor "
                        + "FROM links l "
                        + "JOIN pages sp ON sp.id = l.from_page_id "
                        + "WHERE l.target_workspace = ? AND l.target_project = ? AND l.target_path = ? "
                        + "  AND l.target_resolved "
                        + "ORDER BY l.created_at DESC, l.id DESC",
                relatedMapper(ws, proj), ws, proj, path);
    }

    /**
     * Outgoing links of a page: the targets its body points at. Unresolved (deferred) targets are
     * included with a {@code null} title so a forward link is still visible.
     *
     * @param source the linking page; page-scoped.
     * @return the link targets in insertion order.
     */
    @Transactional(readOnly = true)
    public List<RelatedPage> outgoingLinksOf(Identity source) {
        requirePageScoped(source);
        String ws = source.workspace().value();
        String proj = source.project().value();
        String path = source.page().value();
        return jdbc.query(
                "SELECT l.target_workspace AS w, l.target_project AS p, l.target_path AS path, "
                        + "       tp.title AS title, l.anchor AS anchor "
                        + "FROM links l "
                        + "LEFT JOIN pages tp ON tp.id = l.to_page_id "
                        + "WHERE l.source_workspace = ? AND l.source_project = ? AND l.source_path = ? "
                        + "  AND l.target_path IS NOT NULL "
                        + "ORDER BY l.created_at, l.id",
                relatedMapper(ws, proj), ws, proj, path);
    }

    /** Maps a related-page row, flagging cross-project relative to the given origin coordinates. */
    private static RowMapper<RelatedPage> relatedMapper(String originWs, String originProj) {
        return (rs, n) -> {
            String w = rs.getString("w");
            String p = rs.getString("p");
            boolean cross = !originWs.equals(w) || !originProj.equals(p);
            return new RelatedPage(
                    w, p, rs.getString("path"), rs.getString("title"), rs.getString("anchor"), cross);
        };
    }

    private static void requirePageScoped(Identity identity) {
        if (identity == null || !identity.isPageScoped()) {
            throw new IllegalArgumentException("identity must be page-scoped (path required)");
        }
    }
}
