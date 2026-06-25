package com.agentmemory.mcp;

import com.agentmemory.recall.Scope;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only JDBC queries backing the MCP status/briefing tools and project-scope resolution (issue
 * #17). These are aggregate/auxiliary reads that don't fit the page or recall repositories: lifetime
 * counts, recent-activity windows, the {@code _rules/}/{@code _slots/} page listings a briefing
 * surfaces, and the "most recently active project" used when an MCP call omits an explicit scope
 * (DD-003: project resolved from recent hook activity).
 *
 * <p>All methods are {@code @Transactional(readOnly = true)}; nothing here mutates.
 */
public class McpReadRepository {

    private final JdbcTemplate jdbc;

    public McpReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Resolve the most recently active project from hook capture — the latest of any observation or
     * session across the store (DD-003). Used as the default scope when an MCP request gives no
     * explicit {@code workspace}/{@code project}.
     *
     * @return the most-recently-active {@code (workspace, project)}, or empty if nothing captured yet.
     */
    @Transactional(readOnly = true)
    public Optional<Scope> mostRecentActivityScope() {
        // Union the newest observation and newest session, pick the overall newest (wsq), and read its
        // identity slugs. observations.created_at and sessions.started_at are the activity timestamps.
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT workspace, project FROM ( "
                            + "  SELECT workspace, project, created_at AS at FROM observations "
                            + "  UNION ALL "
                            + "  SELECT workspace, project, started_at AS at FROM sessions "
                            + ") activity ORDER BY at DESC NULLS LAST LIMIT 1",
                    (rs, n) -> Scope.of(rs.getString("workspace"), rs.getString("project"))));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Lifetime counts for a project, for {@code memory_status} / {@code memory_briefing}. */
    @Transactional(readOnly = true)
    public Counts counts(Scope scope) {
        String ws = scope.workspaceSlug();
        String proj = scope.projectSlug();
        long pages = count(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND is_latest", ws, proj);
        long observations = count(
                "SELECT count(*) FROM observations WHERE workspace = ? AND project = ?", ws, proj);
        long sessions = count(
                "SELECT count(*) FROM sessions WHERE workspace = ? AND project = ?", ws, proj);
        long links = count(
                "SELECT count(*) FROM links WHERE source_workspace = ? AND source_project = ?", ws, proj);
        // Inbound dependents (#28): resolved links from any project pointing AT this project's pages —
        // how much other memory depends on this project (the complement of the outgoing `links` count).
        long dependents = count(
                "SELECT count(*) FROM links WHERE target_resolved AND to_page_id IS NOT NULL "
                        + "AND target_workspace = ? AND target_project = ?", ws, proj);
        return new Counts(pages, observations, sessions, links, dependents);
    }

    /**
     * Count observations in the recent window for activity stats, e.g. last 7 or 30 days.
     *
     * @param scope the project.
     * @param days  the trailing window in days.
     * @return number of observations created within {@code days} days.
     */
    @Transactional(readOnly = true)
    public long observationsInLastDays(Scope scope, int days) {
        return count(
                "SELECT count(*) FROM observations WHERE workspace = ? AND project = ? "
                        + "AND created_at >= now() - make_interval(days => ?)",
                scope.workspaceSlug(), scope.projectSlug(), days);
    }

    /**
     * List the latest-page paths under a folder prefix (e.g. {@code _rules/}, {@code _slots/}) for a
     * briefing. Returns at most {@code limit} paths, alphabetically.
     *
     * @param scope  the project.
     * @param prefix the path prefix to match (a literal {@code LIKE prefix%}).
     * @param limit  max paths.
     * @return matching latest-page paths.
     */
    @Transactional(readOnly = true)
    public List<String> latestPathsUnder(Scope scope, String prefix, int limit) {
        return jdbc.query(
                "SELECT path FROM pages WHERE workspace = ? AND project = ? AND is_latest "
                        + "AND path LIKE ? ORDER BY path LIMIT ?",
                (rs, n) -> rs.getString("path"),
                scope.workspaceSlug(), scope.projectSlug(), like(prefix), limit);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    /** Escape LIKE metacharacters in a literal prefix, then append {@code %}. */
    private static String like(String prefix) {
        String escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return escaped + "%";
    }

    /**
     * Lifetime counts for a project.
     *
     * @param pages        latest (non-superseded) pages.
     * @param observations captured observations.
     * @param sessions     capture sessions.
     * @param links        outgoing links from this project's pages.
     * @param dependents   resolved inbound links targeting this project's pages — how much other
     *                     memory depends on this project (#28; the complement of {@code links}).
     */
    public record Counts(long pages, long observations, long sessions, long links, long dependents) {}
}
