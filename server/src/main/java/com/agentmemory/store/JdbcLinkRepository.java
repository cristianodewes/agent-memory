package com.agentmemory.store;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Link;
import com.agentmemory.core.PageId;
import com.agentmemory.core.Uuid7;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcTemplate}-backed {@link LinkRepository} over the {@code links} table (V5). Reindex (#14)
 * uses it to rebuild the wikilink graph; the recall graph arm reads what it writes.
 *
 * <p><strong>Resolution.</strong> A link's target is resolved to the <em>latest</em> page version at
 * the target's {@code (workspace, project, path)}. {@link #insert} resolves eagerly when the target
 * already exists; otherwise it stores the link deferred and {@link #resolveDeferred()} completes it
 * once the target is indexed (forward links, V5 {@code links_unresolved_idx}). All target-identity
 * slugs are written together (the V5 {@code links_target_identity_complete} CHECK), which a
 * page-scoped {@code core.Link} target always satisfies.
 */
public class JdbcLinkRepository implements LinkRepository {

    private final JdbcTemplate jdbc;

    public JdbcLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void insert(PageId fromPageId, Link link) {
        if (fromPageId == null) {
            throw new IllegalArgumentException("fromPageId must not be null");
        }
        if (link == null || link.target() == null || !link.target().isPageScoped()) {
            throw new IllegalArgumentException("link with a page-scoped target is required");
        }
        Identity source = link.source();
        Identity target = link.target();
        String tws = target.workspace().value();
        String tproj = target.project().value();
        String tpath = target.page().value();

        // Resolve against the current latest version of the target page (null if it does not exist).
        UUID toPageId = jdbc.query(
                "SELECT id FROM pages "
                        + "WHERE workspace = ? AND project = ? AND path = ? AND is_latest",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                tws, tproj, tpath);

        jdbc.update(
                "INSERT INTO links "
                        + "(id, from_page_id, source_workspace, source_project, source_path, "
                        + " to_page_id, target_workspace, target_project, target_path, "
                        + " anchor, target_resolved) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Uuid7.randomUuid(),
                fromPageId.value(),
                source.workspace().value(),
                source.project().value(),
                source.page().value(),
                toPageId,
                tws,
                tproj,
                tpath,
                link.anchor(),
                toPageId != null);
    }

    @Override
    @Transactional
    public int deleteLinksFrom(PageId fromPageId) {
        if (fromPageId == null) {
            throw new IllegalArgumentException("fromPageId must not be null");
        }
        return jdbc.update("DELETE FROM links WHERE from_page_id = ?", fromPageId.value());
    }

    @Override
    @Transactional
    public int resolveDeferred() {
        // For every unresolved link whose target now has a latest page version, fill to_page_id and
        // flip target_resolved. The correlated subquery picks that latest version; the WHERE guards
        // keep it idempotent (already-resolved or still-missing links are skipped).
        return jdbc.update(
                "UPDATE links l SET "
                        + "  to_page_id = p.id, "
                        + "  target_resolved = true "
                        + "FROM pages p "
                        + "WHERE NOT l.target_resolved "
                        + "  AND l.target_path IS NOT NULL "
                        + "  AND p.is_latest "
                        + "  AND p.workspace = l.target_workspace "
                        + "  AND p.project = l.target_project "
                        + "  AND p.path = l.target_path");
    }

    @Override
    @Transactional
    public int reresolveTarget(Identity target) {
        if (target == null || !target.isPageScoped()) {
            throw new IllegalArgumentException("target must be page-scoped");
        }
        String tws = target.workspace().value();
        String tproj = target.project().value();
        String tpath = target.page().value();
        // Re-point every link aimed at this (ws, project, path) to its current latest version. The
        // subquery resolves the latest; links whose target version changed (or were deferred) follow.
        return jdbc.update(
                "UPDATE links l SET "
                        + "  to_page_id = (SELECT p.id FROM pages p "
                        + "                 WHERE p.is_latest AND p.workspace = ? "
                        + "                   AND p.project = ? AND p.path = ?), "
                        + "  target_resolved = true "
                        + "WHERE l.target_workspace = ? AND l.target_project = ? AND l.target_path = ? "
                        + "  AND EXISTS (SELECT 1 FROM pages p2 "
                        + "               WHERE p2.is_latest AND p2.workspace = ? "
                        + "                 AND p2.project = ? AND p2.path = ?)",
                tws, tproj, tpath,
                tws, tproj, tpath,
                tws, tproj, tpath);
    }

    @Override
    @Transactional
    public int truncateAll() {
        return jdbc.update("DELETE FROM links");
    }

    @Override
    @Transactional(readOnly = true)
    public long countFrom(Identity source) {
        if (source == null || !source.isPageScoped()) {
            throw new IllegalArgumentException("source must be page-scoped");
        }
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM links "
                        + "WHERE source_workspace = ? AND source_project = ? AND source_path = ?",
                Long.class,
                source.workspace().value(), source.project().value(), source.page().value());
        return n == null ? 0L : n;
    }
}
