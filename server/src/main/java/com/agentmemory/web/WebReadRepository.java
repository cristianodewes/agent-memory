package com.agentmemory.web;

import com.agentmemory.recall.Scope;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-only JDBC queries the {@code /api/v1} API needs that are not already covered by the recall
 * ({@code RecallRepository}), store ({@code PageRepository}) or MCP ({@code McpReadRepository})
 * layers (issue #35): the workspace and project directory listings, and a <em>paginated</em> latest-
 * pages listing with its total count (the page-store {@code listLatest} returns the whole set; the
 * HTTP API pages it server-side). All reads are {@code @Transactional(readOnly = true)}; nothing here
 * mutates — the API is read-only by contract.
 */
public class WebReadRepository {

    private final JdbcTemplate jdbc;

    public WebReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * List all workspace slugs, alphabetically, paginated.
     *
     * @param offset zero-based offset.
     * @param limit  max rows.
     * @return the workspace slugs in this page.
     */
    @Transactional(readOnly = true)
    public List<String> listWorkspaces(int offset, int limit) {
        return jdbc.query(
                "SELECT slug FROM workspaces ORDER BY slug LIMIT ? OFFSET ?",
                (rs, n) -> rs.getString("slug"), limit, offset);
    }

    /** @return the total number of workspaces. */
    @Transactional(readOnly = true)
    public long countWorkspaces() {
        return count("SELECT count(*) FROM workspaces");
    }

    /**
     * List the project slugs in a workspace, alphabetically, paginated.
     *
     * @param workspace the workspace slug.
     * @param offset    zero-based offset.
     * @param limit     max rows.
     * @return the project slugs in this page.
     */
    @Transactional(readOnly = true)
    public List<String> listProjects(String workspace, int offset, int limit) {
        return jdbc.query(
                "SELECT slug FROM projects WHERE workspace = ? ORDER BY slug LIMIT ? OFFSET ?",
                (rs, n) -> rs.getString("slug"), workspace, limit, offset);
    }

    /** @param workspace the workspace slug. @return the number of projects in the workspace. */
    @Transactional(readOnly = true)
    public long countProjects(String workspace) {
        return count("SELECT count(*) FROM projects WHERE workspace = ?", workspace);
    }

    /**
     * The page-version ids of the latest pages in a project, most-recently-updated first, paginated.
     * Returning ids (rather than full rows) lets the controller hydrate each via the shared
     * {@code PageRepository} so there is one page-row mapping (the one that also reads the #24
     * {@code layer} column), not a second copy here.
     *
     * @param scope  the project.
     * @param offset zero-based offset.
     * @param limit  max rows.
     * @return the latest page-version ids in this page, ordered updated_at DESC, id DESC.
     */
    @Transactional(readOnly = true)
    public List<java.util.UUID> latestPageIds(Scope scope, int offset, int limit) {
        return jdbc.query(
                "SELECT id FROM pages WHERE workspace = ? AND project = ? AND is_latest "
                        + "ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, n) -> rs.getObject("id", java.util.UUID.class),
                scope.workspaceSlug(), scope.projectSlug(), limit, offset);
    }

    /** @param scope the project. @return the number of latest pages in the project. */
    @Transactional(readOnly = true)
    public long countLatestPages(Scope scope) {
        return count(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND is_latest",
                scope.workspaceSlug(), scope.projectSlug());
    }

    /**
     * The busiest top-level folders in a project: each distinct first path segment of the latest pages
     * with its page count, most-populated first (issue #85 scent map). Root-level pages (a path with no
     * {@code /}) are grouped under {@code (root)}.
     *
     * @param scope the project.
     * @param limit max folders.
     * @return folder + page-count rows, descending by count then folder name.
     */
    @Transactional(readOnly = true)
    public List<WebDtos.FolderCount> topFolders(Scope scope, int limit) {
        return jdbc.query(
                "SELECT CASE WHEN position('/' in path) > 0 THEN split_part(path, '/', 1) "
                        + "ELSE '(root)' END AS folder, count(*) AS pages "
                        + "FROM pages WHERE workspace = ? AND project = ? AND is_latest "
                        + "GROUP BY 1 ORDER BY count(*) DESC, folder ASC LIMIT ?",
                (rs, n) -> new WebDtos.FolderCount(rs.getString("folder"), rs.getLong("pages")),
                scope.workspaceSlug(), scope.projectSlug(), limit);
    }

    /**
     * The "hub" pages of a project: the latest pages with the most resolved inbound links, most-linked
     * first (issue #85 scent map). Inbound links are counted per target {@code path} (page identity,
     * across versions) over resolved links that target this project, then joined to the latest page for
     * its title. Pages with no inbound links are omitted.
     *
     * @param scope the project.
     * @param limit max hub pages.
     * @return path + title + inbound-link count rows, descending by inbound then path.
     */
    @Transactional(readOnly = true)
    public List<WebDtos.HubPage> hubPages(Scope scope, int limit) {
        return jdbc.query(
                "SELECT p.path AS path, p.title AS title, c.inbound AS inbound "
                        + "FROM (SELECT target_path, count(*) AS inbound FROM links "
                        + "      WHERE target_resolved AND to_page_id IS NOT NULL "
                        + "        AND target_workspace = ? AND target_project = ? "
                        + "      GROUP BY target_path) c "
                        + "JOIN pages p ON p.workspace = ? AND p.project = ? "
                        + "            AND p.path = c.target_path AND p.is_latest "
                        + "ORDER BY c.inbound DESC, p.path ASC LIMIT ?",
                (rs, n) -> new WebDtos.HubPage(
                        rs.getString("path"), rs.getString("title"), rs.getLong("inbound")),
                scope.workspaceSlug(), scope.projectSlug(),
                scope.workspaceSlug(), scope.projectSlug(), limit);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }
}
