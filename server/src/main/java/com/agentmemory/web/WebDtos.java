package com.agentmemory.web;

import com.agentmemory.mcp.McpReadRepository;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import java.util.List;

/**
 * The stable, flat JSON shapes the read-only {@code /api/v1} HTTP API returns (issue #35,
 * ARCHITECTURE §5.2). Kept deliberately separate from the domain / recall / MCP records: the HTTP
 * wire format is a published contract for external frontends (#36 and third parties) and must not
 * shift when an internal type changes. These are plain records with no framework annotations, so they
 * serialize cleanly through Spring Boot's default Jackson mapper.
 *
 * <p>Where the same information is already shaped for the MCP tools (#17), the field set is mirrored
 * intentionally so the two surfaces agree; the search hit shape, in particular, mirrors {@code
 * memory_query} so an API {@code /search} caller and an MCP {@code memory_query} caller see identical
 * results (issue #35 acceptance).
 */
public final class WebDtos {

    private WebDtos() {}

    /**
     * A page of results with the cursor metadata a client needs to fetch the next page. Generic over
     * the item type so every list endpoint shares one pagination envelope.
     *
     * @param items  the items in this page (never null).
     * @param limit  the page size that was applied.
     * @param offset the zero-based offset of the first item in this page.
     * @param total  the total number of items across all pages.
     * @param <T>    the item type.
     */
    public record Page<T>(List<T> items, int limit, int offset, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** A workspace entry in {@code GET /api/v1/workspaces}. */
    public record WorkspaceView(String workspace) {}

    /** A project entry in {@code GET /api/v1/projects?workspace=...}. */
    public record ProjectView(String workspace, String project) {}

    /** The resolved scope echoed back so a caller can see which project answered. */
    public record ScopeView(String workspace, String project) {
        static ScopeView of(Scope s) {
            return new ScopeView(s.workspaceSlug(), s.projectSlug());
        }
    }

    /**
     * A page summary (metadata only, no body) for listings and {@code recent}. Carries the retention
     * {@code layer} (#24) so a frontend can show how a page is classified.
     */
    public record PageSummary(
            String path, String title, String layer, boolean latest,
            long accessCount, String createdAt, String updatedAt) {
        static PageSummary of(PageRecord p) {
            return new PageSummary(
                    p.page().path().value(),
                    p.page().title(),
                    p.layer().wire(),
                    p.isLatest(),
                    p.accessCount(),
                    p.page().createdAt().toString(),
                    p.page().updatedAt().toString());
        }
    }

    /** A page with its full body for {@code GET .../pages/{path}}. */
    public record PageDetail(
            String path, String title, String body, String layer, boolean latest,
            long accessCount, String createdAt, String updatedAt) {
        static PageDetail of(PageRecord p) {
            return new PageDetail(
                    p.page().path().value(),
                    p.page().title(),
                    p.page().body(),
                    p.layer().wire(),
                    p.isLatest(),
                    p.accessCount(),
                    p.page().createdAt().toString(),
                    p.page().updatedAt().toString());
        }
    }

    /**
     * One hit in a {@code /search} response — mirrors the MCP {@code memory_query} hit shape so the
     * two surfaces return the same hybrid results (issue #35 acceptance).
     *
     * @param source  the producing arm / hit source (e.g. {@code PAGE}, {@code RAW_OBSERVATION}).
     * @param id      the page-version id (PAGE) or observation id (RAW_OBSERVATION).
     * @param path    project-relative page path for a PAGE hit; {@code null} for a raw-observation hit.
     * @param title   page title (PAGE) or synthetic label (RAW_OBSERVATION).
     * @param kind    observation kind for a RAW_OBSERVATION hit; {@code null} for a PAGE hit.
     * @param score   the fused relevance score (RRF).
     * @param rank    the 1-based position in the returned list.
     * @param snippet an HTML-marked excerpt (matches wrapped in {@code <mark>…</mark>}).
     */
    public record SearchHit(
            String source, String id, String path, String title, String kind,
            double score, int rank, String snippet) {
        static SearchHit of(RecallHit h) {
            return new SearchHit(
                    h.source().name(), h.id(), h.path(), h.title(), h.kind(),
                    h.score(), h.rank(), h.snippet());
        }
    }

    /**
     * A {@code /search} result: the scope(s) searched, whether the bounded raw-observation fallback
     * fired, and the ranked hits.
     *
     * @param scopes      the scope(s) the query ran against (one for the single-project case).
     * @param rawFallback {@code true} iff the hits are the raw-observation fallback.
     * @param count       the number of hits.
     * @param hits        the ranked hits.
     */
    public record SearchResult(
            List<ScopeView> scopes, boolean rawFallback, int count, List<SearchHit> hits) {
        static SearchResult of(Scope scope, RecallResult r) {
            return new SearchResult(
                    List.of(ScopeView.of(scope)),
                    r.rawFallback(),
                    r.hits().size(),
                    r.hits().stream().map(SearchHit::of).toList());
        }
    }

    /** The POST {@code /search} request body: free text plus optional explicit scopes. */
    public record SearchRequest(String q, List<ScopeView> scopes, Integer limit) {}

    /**
     * A structured project briefing (no LLM) — counts, recent activity windows, the {@code _rules/} /
     * {@code _slots/} listings and the most recent pages. Mirrors the MCP {@code memory_briefing}.
     */
    public record BriefingView(
            ScopeView scope,
            long pages,
            long observations,
            long sessions,
            long links,
            long dependents,
            long observationsLast7Days,
            long observationsLast30Days,
            List<String> rules,
            List<String> slots,
            List<PageSummary> recent) {

        static BriefingView of(
                Scope scope, McpReadRepository.Counts c,
                long last7, long last30,
                List<String> rules, List<String> slots, List<PageSummary> recent) {
            return new BriefingView(
                    ScopeView.of(scope), c.pages(), c.observations(), c.sessions(), c.links(),
                    c.dependents(), last7, last30, rules, slots, recent);
        }
    }

    /** One folder in the scent map: a top-level path segment and how many latest pages sit under it. */
    public record FolderCount(String folder, long pages) {}

    /** One hub page in the scent map: a heavily-referenced page and its resolved inbound-link count. */
    public record HubPage(String path, String title, long inbound) {}

    /**
     * {@code GET .../scent}: a compact "what memory exists" orientation map (issue #85) — the busiest
     * folders and the most-linked hub pages — so an agent (or the SessionStart injection) can see the
     * shape of a project's memory without reading it. No LLM, read-only.
     */
    public record ScentView(ScopeView scope, List<FolderCount> folders, List<HubPage> hubs) {}

    /** Memory-health rollup for {@code overview} (lifetime counts + recent activity). */
    public record HealthView(
            long pages, long observations, long sessions, long links, long dependents,
            long observationsLast7Days, long observationsLast30Days) {}

    /**
     * The {@code overview} bundle: an open handoff (seam — #22, {@code null} until handoffs land), the
     * project briefing, and a memory-health rollup, in one response (issue #35 acceptance).
     *
     * @param scope    the resolved scope.
     * @param handoff  the open handoff, or {@code null} when none exists / the feature is not built (#22).
     * @param briefing the structured briefing.
     * @param health   the memory-health rollup.
     */
    public record OverviewView(
            ScopeView scope, Object handoff, BriefingView briefing, HealthView health) {}

    /** One node (a latest page) in the {@code GET /api/v1/graph} response. */
    public record GraphNodeView(String id, String workspace, String project, String path, String title) {
        static GraphNodeView of(com.agentmemory.graph.GraphNode n) {
            return new GraphNodeView(n.id(), n.workspace(), n.project(), n.path(), n.title());
        }
    }

    /** One resolved directed edge (a wikilink between two existing pages) in {@code GET /api/v1/graph}. */
    public record GraphEdgeView(String from, String to, String anchor, boolean crossProject) {
        static GraphEdgeView of(com.agentmemory.graph.GraphEdge e) {
            return new GraphEdgeView(e.from(), e.to(), e.anchor(), e.crossProject());
        }
    }

    /**
     * One unresolved wikilink in {@code GET /api/v1/graph} — a forward reference whose target does not
     * (yet) exist — classified {@code dangling} (stale, a lint signal) vs deferred (recent).
     */
    public record DanglingRefView(
            String from, String fromWorkspace, String fromProject, String fromPath,
            String target, String anchor, long ageSeconds, boolean dangling) {
        static DanglingRefView of(com.agentmemory.graph.DanglingRef d) {
            return new DanglingRefView(
                    d.fromId(), d.fromWorkspace(), d.fromProject(), d.fromPath(),
                    d.targetId(), d.anchor(), d.ageSeconds(), d.dangling());
        }
    }

    /**
     * The unified dependency graph for {@code GET /api/v1/graph} (issue #28). Edges are the
     * <strong>resolved</strong> wikilinks in the requested page (so every endpoint is a real node);
     * unresolved links are reported separately under {@code dangling} (classified stale vs deferred),
     * the dangling-reference lint. {@code scope} echoes the project filter, or is {@code null} for the
     * full cross-project graph. The graph is paged by edges: {@code limit}/{@code offset}/
     * {@code totalEdges} let a client page through a large store, and {@code danglingCount}/
     * {@code deferredCount} report the unresolved-link totals for the scope independent of the
     * (capped) {@code dangling} sample.
     *
     * @param scope         the project filter echoed back, or {@code null} for cross-project.
     * @param nodes         the distinct page nodes referenced by {@code edges}.
     * @param edges         the resolved edges in this page, deterministically ordered.
     * @param dangling      a capped sample of unresolved links (most stale first), the lint output.
     * @param limit         the edge page size applied.
     * @param offset        the zero-based offset of the first edge in this page.
     * @param totalEdges    the total resolved edges matching the query, across all pages.
     * @param danglingCount the total stale unresolved links for the scope.
     * @param deferredCount the total recent (still-deferred) unresolved links for the scope.
     */
    public record GraphView(
            ScopeView scope,
            List<GraphNodeView> nodes,
            List<GraphEdgeView> edges,
            List<DanglingRefView> dangling,
            int limit,
            int offset,
            long totalEdges,
            long danglingCount,
            long deferredCount) {

        static GraphView of(
                Scope scope,
                com.agentmemory.graph.DependencyGraph g,
                List<com.agentmemory.graph.DanglingRef> dangling,
                com.agentmemory.graph.GraphService.Unresolved unresolved) {
            return new GraphView(
                    scope == null ? null : ScopeView.of(scope),
                    g.nodes().stream().map(GraphNodeView::of).toList(),
                    g.edges().stream().map(GraphEdgeView::of).toList(),
                    dangling.stream().map(DanglingRefView::of).toList(),
                    g.limit(), g.offset(), g.totalEdges(),
                    unresolved.dangling(), unresolved.deferred());
        }
    }
}
