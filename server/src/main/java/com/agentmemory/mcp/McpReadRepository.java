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
     * explicit {@code workspace}/{@code project}. This is the {@code single_slot} default.
     *
     * @return the most-recently-active {@code (workspace, project)}, or empty if nothing captured yet.
     */
    @Transactional(readOnly = true)
    public Optional<Scope> mostRecentActivityScope() {
        return mostRecentActivityScope(null);
    }

    /**
     * Most recently active project, optionally restricted to one {@code actor} — the {@code per_actor}
     * isolation of issue #39's {@code auto_scope}. With {@code actor == null}/blank this is the global
     * newest activity ({@code single_slot}, identical to {@link #mostRecentActivityScope()}). With an
     * actor, only that user's {@code observations} are considered — {@code sessions} carry no actor — so
     * a shared server resolves each user's no-scope call to their own lane.
     *
     * @param actor the authenticated user to restrict to, or {@code null}/blank for global.
     * @return the most-recently-active {@code (workspace, project)}, or empty if none matches.
     */
    @Transactional(readOnly = true)
    public Optional<Scope> mostRecentActivityScope(String actor) {
        try {
            if (actor == null || actor.isBlank()) {
                // Global: union the newest observation and newest session, pick the overall newest, and
                // read its identity slugs. observations.created_at / sessions.started_at are the
                // activity timestamps.
                return Optional.ofNullable(jdbc.queryForObject(
                        "SELECT workspace, project FROM ( "
                                + "  SELECT workspace, project, created_at AS at FROM observations "
                                + "  UNION ALL "
                                + "  SELECT workspace, project, started_at AS at FROM sessions "
                                + ") activity ORDER BY at DESC NULLS LAST LIMIT 1",
                        (rs, n) -> Scope.of(rs.getString("workspace"), rs.getString("project"))));
            }
            // Per-actor: only observations are attributed, so pick this user's newest observation.
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT workspace, project FROM observations WHERE actor = ? "
                            + "ORDER BY created_at DESC LIMIT 1",
                    (rs, n) -> Scope.of(rs.getString("workspace"), rs.getString("project")),
                    actor));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Most recently active project <em>within a single capture session</em> — the {@code session_aware}
     * isolation of issue #87's {@code auto_scope}. Keyed on the {@code session_id} the native hook
     * reports (and writes on every {@code observations} row), so two sessions of the same user — even
     * concurrent ones in different projects — resolve their no-scope MCP calls to their own session's
     * project rather than to each other's. Unions the session's observations with the {@code sessions}
     * row itself so a freshly-started session (its scope known before any further observation) still
     * resolves.
     *
     * @param sessionId the capture session id (a UUID string); a {@code null}/blank id yields empty (the
     *     caller {@code ScopeResolver} fail-fasts before reaching here when no session id is present).
     * @return the session's most-recently-active {@code (workspace, project)}, or empty if the session
     *     id matches no captured activity.
     */
    @Transactional(readOnly = true)
    public Optional<Scope> mostRecentActivityScopeForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT workspace, project FROM ( "
                            + "  SELECT workspace, project, created_at AS at FROM observations "
                            + "    WHERE session_id = ?::uuid "
                            + "  UNION ALL "
                            + "  SELECT workspace, project, started_at AS at FROM sessions "
                            + "    WHERE id = ?::uuid "
                            + ") activity ORDER BY at DESC NULLS LAST LIMIT 1",
                    (rs, n) -> Scope.of(rs.getString("workspace"), rs.getString("project")),
                    sessionId, sessionId));
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

    /**
     * Whole days since the project's most recent activity (latest observation or session), for
     * calibrating {@code memory_explore}'s verbosity (issue #19). Returns empty when the project has no
     * captured activity at all (a brand-new project).
     *
     * @param scope the project.
     * @return days since the last activity, or empty if there is none.
     */
    @Transactional(readOnly = true)
    public Optional<Long> daysSinceLastActivity(Scope scope) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT floor(extract(epoch FROM (now() - max(at))) / 86400)::bigint FROM ( "
                            + "  SELECT created_at AS at FROM observations WHERE workspace = ? AND project = ? "
                            + "  UNION ALL "
                            + "  SELECT started_at AS at FROM sessions WHERE workspace = ? AND project = ? "
                            + ") activity",
                    Long.class,
                    scope.workspaceSlug(), scope.projectSlug(),
                    scope.workspaceSlug(), scope.projectSlug()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
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
