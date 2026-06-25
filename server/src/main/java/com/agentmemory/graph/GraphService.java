package com.agentmemory.graph;

import com.agentmemory.recall.Scope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the unified, cross-project dependency graph from the {@code links} table (issue #28;
 * ARCHITECTURE §5.2). Links are maintained by the #27 {@code WikiLinkService} (the single authority for
 * the table) — this service is the read/aggregate side that turns those rows into a graph, a
 * dangling-reference lint, and the dependent counts a briefing surfaces.
 *
 * <p><strong>Edges are resolved links only.</strong> An edge's target is always an existing page, so
 * the returned node/edge set is internally consistent (every endpoint is a real node). Unresolved
 * links — a forward reference whose target does not exist yet — are not edges; they are reported by
 * {@link #danglingReport}, classified <em>deferred</em> (recent, may still resolve) vs
 * <em>dangling</em> (older than {@link #danglingAfter}, a likely typo / never-created target).
 *
 * <p><strong>Bounded.</strong> The graph is paged by its edges ({@link GraphQuery#limit}/{@code
 * offset}) so a large store never materializes wholesale; the dangling report is likewise capped.
 *
 * <p>All reads are {@code @Transactional(readOnly = true)}.
 */
public class GraphService {

    /** Default staleness cutoff: an unresolved link older than this is classified dangling, not deferred. */
    public static final Duration DEFAULT_DANGLING_AFTER = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final Duration danglingAfter;

    public GraphService(JdbcTemplate jdbc) {
        this(jdbc, DEFAULT_DANGLING_AFTER);
    }

    public GraphService(JdbcTemplate jdbc, Duration danglingAfter) {
        this.jdbc = jdbc;
        this.danglingAfter = danglingAfter == null ? DEFAULT_DANGLING_AFTER : danglingAfter;
    }

    // --- graph -------------------------------------------------------------------------------------

    /**
     * Read a page of the dependency graph: the resolved edges in the window plus the nodes they touch.
     *
     * @param query the scope filter (or null for cross-project) and edge pagination; never null.
     * @return the edges, their endpoint nodes, and the total resolved-edge count for the query.
     */
    @Transactional(readOnly = true)
    public DependencyGraph graph(GraphQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        ScopeFilter f = ScopeFilter.of(query.scope());

        long total = count(
                "SELECT count(*) FROM links l "
                        + "WHERE l.target_resolved AND l.to_page_id IS NOT NULL" + f.edgeWhere(),
                f.args());

        // Resolved edges, joined to both endpoint pages so the nodes hydrate from the SAME result set
        // (no second round-trip). Order is fully deterministic (source id, target id, link id) for
        // stable pagination. Mapped to the intermediate EdgeRow that carries every endpoint column.
        List<EdgeRow> rows = jdbc.query(
                "SELECT l.source_workspace AS sw, l.source_project AS sp, l.source_path AS spath, "
                        + "       l.target_workspace AS tw, l.target_project AS tp, l.target_path AS tpath, "
                        + "       sp_pg.title AS s_title, tp_pg.title AS t_title, l.anchor AS anchor "
                        + "FROM links l "
                        + "JOIN pages sp_pg ON sp_pg.id = l.from_page_id "
                        + "JOIN pages tp_pg ON tp_pg.id = l.to_page_id "
                        + "WHERE l.target_resolved AND l.to_page_id IS NOT NULL" + f.edgeWhere() + " "
                        + "ORDER BY sw, sp, spath, tw, tp, tpath, l.id "
                        + "LIMIT ? OFFSET ?",
                EDGE_ROW,
                flatten(f.args(), query.limit(), query.offset()));

        // Nodes = the distinct endpoints of the returned edges (self-contained subgraph). Keyed by id,
        // first occurrence wins (titles are identical per id within a snapshot). Edges derive from the
        // same rows so endpoints and edge list cannot drift.
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>(rows.size());
        for (EdgeRow r : rows) {
            nodes.putIfAbsent(r.fromId(), new GraphNode(
                    r.fromId(), r.sw(), r.sp(), r.spath(), r.sTitle()));
            nodes.putIfAbsent(r.toId(), new GraphNode(
                    r.toId(), r.tw(), r.tp(), r.tpath(), r.tTitle()));
            edges.add(new GraphEdge(r.fromId(), r.toId(), r.anchor(), r.crossProject()));
        }
        return new DependencyGraph(
                new ArrayList<>(nodes.values()), edges, query.limit(), query.offset(), total);
    }

    // --- dangling-reference lint -------------------------------------------------------------------

    /**
     * The unresolved links ({@code to_page_id IS NULL}) with a real intended target, classified
     * deferred vs dangling by age, most-stale first, capped at {@code limit}. A bare recorded anchor
     * (no target identity) is not included — only links that name a target that failed to resolve.
     *
     * @param scope narrow to links sourced from this project, or null for cross-project.
     * @param limit max rows.
     * @return the unresolved links with their classification.
     */
    @Transactional(readOnly = true)
    public List<DanglingRef> danglingReport(Scope scope, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        ScopeFilter f = ScopeFilter.ofSource(scope);
        long cutoffSeconds = danglingAfter.toSeconds();
        return jdbc.query(
                "SELECT l.source_workspace AS sw, l.source_project AS sp, l.source_path AS spath, "
                        + "       l.target_workspace AS tw, l.target_project AS tp, l.target_path AS tpath, "
                        + "       l.anchor AS anchor, "
                        + "       EXTRACT(EPOCH FROM (now() - l.created_at))::bigint AS age_seconds "
                        + "FROM links l "
                        + "WHERE l.to_page_id IS NULL AND NOT l.target_resolved "
                        + "  AND l.target_path IS NOT NULL" + f.sourceWhere() + " "
                        + "ORDER BY l.created_at ASC, l.id "
                        + "LIMIT ?",
                (rs, n) -> {
                    long age = rs.getLong("age_seconds");
                    String fromId = id(rs.getString("sw"), rs.getString("sp"), rs.getString("spath"));
                    String targetId = id(rs.getString("tw"), rs.getString("tp"), rs.getString("tpath"));
                    return new DanglingRef(
                            fromId, rs.getString("sw"), rs.getString("sp"), rs.getString("spath"),
                            targetId, rs.getString("anchor"), age, age >= cutoffSeconds);
                },
                flatten(f.args(), limit));
    }

    /** Counts of unresolved links split by classification, for a scope (or cross-project when null). */
    @Transactional(readOnly = true)
    public Unresolved unresolvedCounts(Scope scope) {
        ScopeFilter f = ScopeFilter.ofSource(scope);
        long cutoff = danglingAfter.toSeconds();
        long dangling = count(
                "SELECT count(*) FROM links l WHERE l.to_page_id IS NULL AND NOT l.target_resolved "
                        + "AND l.target_path IS NOT NULL "
                        + "AND EXTRACT(EPOCH FROM (now() - l.created_at)) >= ?" + f.sourceWhere(),
                flatten(new Object[] {cutoff}, f.args()));
        long deferred = count(
                "SELECT count(*) FROM links l WHERE l.to_page_id IS NULL AND NOT l.target_resolved "
                        + "AND l.target_path IS NOT NULL "
                        + "AND EXTRACT(EPOCH FROM (now() - l.created_at)) < ?" + f.sourceWhere(),
                flatten(new Object[] {cutoff}, f.args()));
        return new Unresolved(deferred, dangling);
    }

    // --- dependent counts (for briefing #17) -------------------------------------------------------

    /**
     * The number of resolved links pointing <em>into</em> a project's pages — how many other pages
     * (in any project) depend on this project (issue #28 → briefing). Counts edges, not distinct
     * dependents, mirroring the link-count semantics already in a briefing.
     *
     * @param scope the project whose inbound dependents to count.
     * @return the count of resolved inbound links targeting this project's pages.
     */
    @Transactional(readOnly = true)
    public long dependentCount(Scope scope) {
        return count(
                "SELECT count(*) FROM links WHERE target_resolved AND to_page_id IS NOT NULL "
                        + "AND target_workspace = ? AND target_project = ?",
                scope.workspaceSlug(), scope.projectSlug());
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static String id(String ws, String proj, String path) {
        return ws + "/" + proj + "/" + path;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    /** Concatenate a base arg array with extra trailing args into one Object[]. */
    private static Object[] flatten(Object[] base, Object... extra) {
        Object[] out = new Object[base.length + extra.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    // Intermediate row carrying both endpoints' identity + titles, before splitting into node/edge.
    private record EdgeRow(
            String sw, String sp, String spath, String sTitle,
            String tw, String tp, String tpath, String tTitle, String anchor) {
        String fromId() {
            return GraphService.id(sw, sp, spath);
        }

        String toId() {
            return GraphService.id(tw, tp, tpath);
        }

        boolean crossProject() {
            return !sw.equals(tw) || !sp.equals(tp);
        }
    }

    // Carries every endpoint column so graph() can build both the node set and the edge list from one
    // result set (no second query, no drift between endpoints and edges).
    private static final RowMapper<EdgeRow> EDGE_ROW = (rs, n) -> new EdgeRow(
            rs.getString("sw"), rs.getString("sp"), rs.getString("spath"), rs.getString("s_title"),
            rs.getString("tw"), rs.getString("tp"), rs.getString("tpath"), rs.getString("t_title"),
            rs.getString("anchor"));

    /** Deferred vs dangling unresolved-link counts. */
    public record Unresolved(long deferred, long dangling) {}

    /** Builds the WHERE fragment + bind args for an optional scope filter on graph edges/sources. */
    private record ScopeFilter(String edgeWhere, String sourceWhere, Object[] args) {
        static ScopeFilter of(Scope scope) {
            if (scope == null) {
                return new ScopeFilter("", "", new Object[0]);
            }
            // An edge "touches" the scope if either endpoint is in it.
            String ws = scope.workspaceSlug();
            String proj = scope.projectSlug();
            String edge = " AND ((l.source_workspace = ? AND l.source_project = ?) "
                    + "OR (l.target_workspace = ? AND l.target_project = ?))";
            return new ScopeFilter(edge, "", new Object[] {ws, proj, ws, proj});
        }

        static ScopeFilter ofSource(Scope scope) {
            if (scope == null) {
                return new ScopeFilter("", "", new Object[0]);
            }
            String src = " AND l.source_workspace = ? AND l.source_project = ?";
            return new ScopeFilter("", src, new Object[] {scope.workspaceSlug(), scope.projectSlug()});
        }
    }
}
