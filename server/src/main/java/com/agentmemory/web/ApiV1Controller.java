package com.agentmemory.web;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.graph.DependencyGraph;
import com.agentmemory.graph.GraphQuery;
import com.agentmemory.graph.GraphService;
import com.agentmemory.mcp.McpReadRepository;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only {@code /api/v1} JSON API (issue #35, ARCHITECTURE §5.2): a thin HTTP surface over the
 * recall, store and MCP read layers so external frontends (and the embedded web UI #36) read the
 * memory without touching Postgres or the wiki files. Every route is a {@code GET} (or the
 * {@code POST /search} variant whose body is a query, not a mutation) — nothing here writes.
 *
 * <h2>Reuse, not duplication</h2>
 * Search delegates to {@link RecallService} (#15/#16) so {@code /search} returns the <em>same</em>
 * hybrid results as the MCP {@code memory_query} (issue acceptance); briefings reuse the #17
 * {@link McpReadRepository} aggregates; pages come from the shared {@link PageRepository} (so the #24
 * {@code layer} column is read by one mapping). The only new SQL is the workspace/project directory
 * and paginated page listings in {@link WebReadRepository}.
 *
 * <h2>DB-less context</h2>
 * The DB-backed collaborators are injected via {@link ObjectProvider} (exactly as {@code
 * HookController}) so the web/config smoke context — which excludes the DataSource — still constructs
 * this controller; every endpoint then answers {@code 503 Service Unavailable} instead of the context
 * failing to start.
 *
 * <h2>Pagination</h2>
 * List endpoints accept {@code limit} (1..{@value #MAX_LIMIT}, default {@value #DEFAULT_LIMIT}) and
 * {@code offset} (>= 0) and return a {@link WebDtos.Page} envelope carrying {@code items/limit/offset/
 * total}.
 *
 * <h2>Seams</h2>
 * {@code overview.handoff} is {@code null} until handoffs land (#22); {@code GET /graph} returns an
 * empty, well-typed graph until the unified graph endpoint (#28) — both documented, neither a 404.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiV1Controller {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int DEFAULT_DANGLING_LIMIT = 50;
    static final int MAX_DANGLING_LIMIT = 500;

    private final ObjectProvider<RecallService> recall;
    private final ObjectProvider<PageRepository> pages;
    private final ObjectProvider<McpReadRepository> reads;
    private final ObjectProvider<WebReadRepository> webReads;
    private final ObjectProvider<GraphService> graphs;

    public ApiV1Controller(
            ObjectProvider<RecallService> recall,
            ObjectProvider<PageRepository> pages,
            ObjectProvider<McpReadRepository> reads,
            ObjectProvider<WebReadRepository> webReads,
            ObjectProvider<GraphService> graphs) {
        this.recall = recall;
        this.pages = pages;
        this.reads = reads;
        this.webReads = webReads;
        this.graphs = graphs;
    }

    // --- directory: workspaces / projects ------------------------------------------------------

    @GetMapping("/workspaces")
    public ResponseEntity<?> workspaces(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        if (notWired()) {
            return unavailable();
        }
        int lim = clampLimit(limit);
        int off = clampOffset(offset);
        WebReadRepository wr = webReads.getObject();
        List<WebDtos.WorkspaceView> items = wr.listWorkspaces(off, lim).stream()
                .map(WebDtos.WorkspaceView::new).toList();
        return ResponseEntity.ok(new WebDtos.Page<>(items, lim, off, wr.countWorkspaces()));
    }

    @GetMapping("/projects")
    public ResponseEntity<?> projects(
            @RequestParam String workspace,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        if (notWired()) {
            return unavailable();
        }
        int lim = clampLimit(limit);
        int off = clampOffset(offset);
        WebReadRepository wr = webReads.getObject();
        List<WebDtos.ProjectView> items = wr.listProjects(workspace, off, lim).stream()
                .map(p -> new WebDtos.ProjectView(workspace, p)).toList();
        return ResponseEntity.ok(new WebDtos.Page<>(items, lim, off, wr.countProjects(workspace)));
    }

    // --- pages ---------------------------------------------------------------------------------

    @GetMapping("/workspaces/{ws}/projects/{p}/pages")
    public ResponseEntity<?> listPages(
            @PathVariable String ws,
            @PathVariable String p,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset) {
        if (notWired()) {
            return unavailable();
        }
        Scope scope = Scope.of(ws, p);
        int lim = clampLimit(limit);
        int off = clampOffset(offset);
        WebReadRepository wr = webReads.getObject();
        PageRepository pr = pages.getObject();
        List<WebDtos.PageSummary> items = new ArrayList<>();
        for (PageId id : wr.latestPageIds(scope, off, lim).stream().map(PageId::new).toList()) {
            pr.findById(id).map(WebDtos.PageSummary::of).ifPresent(items::add);
        }
        return ResponseEntity.ok(new WebDtos.Page<>(items, lim, off, wr.countLatestPages(scope)));
    }

    /**
     * Read one page's full body. The {@code {path}} variable is a wildcard ({@code **}) because a page
     * path contains slashes (e.g. {@code concepts/recall.md}); Spring would otherwise stop at the
     * first segment.
     */
    @GetMapping("/workspaces/{ws}/projects/{p}/pages/{*path}")
    public ResponseEntity<?> readPage(
            @PathVariable String ws,
            @PathVariable String p,
            @PathVariable String path) {
        if (notWired()) {
            return unavailable();
        }
        // {*path} captures with a leading slash (e.g. "/concepts/recall.md"); strip it before
        // normalizing into the typed PagePath.
        String raw = path.startsWith("/") ? path.substring(1) : path;
        if (raw.isBlank()) {
            return ResponseEntity.badRequest().body(error("page path is required"));
        }
        Identity id = Identity.ofPage(Scope.of(ws, p).workspace(), Scope.of(ws, p).project(),
                PagePath.of(raw));
        Optional<PageRecord> page = pages.getObject().readLatest(id);
        return page.<ResponseEntity<?>>map(pr -> ResponseEntity.ok(WebDtos.PageDetail.of(pr)))
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(error("no page at path '" + raw + "' in " + ws + "/" + p)));
    }

    // --- recent / briefing ---------------------------------------------------------------------

    @GetMapping("/workspaces/{ws}/projects/{p}/recent")
    public ResponseEntity<?> recent(
            @PathVariable String ws,
            @PathVariable String p,
            @RequestParam(required = false) Integer limit) {
        if (notWired()) {
            return unavailable();
        }
        Scope scope = Scope.of(ws, p);
        int lim = clampLimit(limit);
        // listLatest already orders updated_at DESC; cap to the requested limit.
        List<WebDtos.PageSummary> items = pages.getObject()
                .listLatest(scope.workspace(), scope.project()).stream()
                .limit(lim).map(WebDtos.PageSummary::of).toList();
        return ResponseEntity.ok(new WebDtos.Page<>(
                items, lim, 0, webReads.getObject().countLatestPages(scope)));
    }

    @GetMapping("/workspaces/{ws}/projects/{p}/briefing")
    public ResponseEntity<?> briefing(
            @PathVariable String ws,
            @PathVariable String p,
            @RequestParam(required = false) Integer limit) {
        if (notWired()) {
            return unavailable();
        }
        return ResponseEntity.ok(buildBriefing(Scope.of(ws, p), clampLimit(limit)));
    }

    // --- scent (orientation map: busiest folders + hub pages) ----------------------------------

    @GetMapping("/workspaces/{ws}/projects/{p}/scent")
    public ResponseEntity<?> scent(
            @PathVariable String ws,
            @PathVariable String p,
            @RequestParam(required = false) Integer folders,
            @RequestParam(required = false) Integer hubs) {
        if (notWired()) {
            return unavailable();
        }
        Scope scope = Scope.of(ws, p);
        WebReadRepository wr = webReads.getObject();
        return ResponseEntity.ok(new WebDtos.ScentView(
                WebDtos.ScopeView.of(scope),
                wr.topFolders(scope, clampLimit(folders)),
                wr.hubPages(scope, clampLimit(hubs))));
    }

    // --- overview (bundle: handoff + briefing + health) ----------------------------------------

    @GetMapping("/workspaces/{ws}/overview")
    public ResponseEntity<?> overview(
            @PathVariable String ws,
            @RequestParam String project,
            @RequestParam(required = false) Integer limit) {
        if (notWired()) {
            return unavailable();
        }
        Scope scope = Scope.of(ws, project);
        int lim = clampLimit(limit);
        WebDtos.BriefingView briefing = buildBriefing(scope, lim);
        WebDtos.HealthView health = new WebDtos.HealthView(
                briefing.pages(), briefing.observations(), briefing.sessions(), briefing.links(),
                briefing.dependents(),
                briefing.observationsLast7Days(), briefing.observationsLast30Days());
        // handoff is a seam (#22): no open handoff is modeled yet, so null until handoffs land.
        return ResponseEntity.ok(new WebDtos.OverviewView(
                WebDtos.ScopeView.of(scope), /* handoff */ null, briefing, health));
    }

    // --- search (GET + POST), same hybrid recall as memory_query -------------------------------

    @GetMapping("/search")
    public ResponseEntity<?> searchGet(
            @RequestParam String q,
            @RequestParam String workspace,
            @RequestParam String project,
            @RequestParam(required = false) Integer limit) {
        if (notWired()) {
            return unavailable();
        }
        return ResponseEntity.ok(runSearch(Scope.of(workspace, project), q, clampLimit(limit)));
    }

    @PostMapping("/search")
    public ResponseEntity<?> searchPost(@RequestBody WebDtos.SearchRequest body) {
        if (notWired()) {
            return unavailable();
        }
        if (body == null || body.q() == null || body.q().isBlank()) {
            return ResponseEntity.badRequest().body(error("'q' is required"));
        }
        if (body.scopes() == null || body.scopes().isEmpty()) {
            return ResponseEntity.badRequest().body(error(
                    "at least one scope { workspace, project } is required"));
        }
        // Single-project recall today (#15); multi-scope fan-out is #29. Use the first scope and
        // echo it back. Keeping the request shape plural means #29 can widen this with no wire change.
        WebDtos.ScopeView s = body.scopes().get(0);
        int lim = clampLimit(body.limit());
        return ResponseEntity.ok(runSearch(Scope.of(s.workspace(), s.project()), body.q(), lim));
    }

    // --- graph (unified cross-project dependency graph, #28) -----------------------------------

    /**
     * The unified dependency graph (issue #28): the resolved wikilink edges and the page nodes they
     * touch, plus the dangling-reference lint (unresolved links classified stale vs deferred).
     *
     * <p>Cross-project by default; pass <em>both</em> {@code workspace} and {@code project} to narrow
     * to the edges touching that project (its cross-project edges in or out included). The graph is
     * paged by edges ({@code limit}/{@code offset}); {@code danglingLimit} caps the lint sample
     * (default {@value #DEFAULT_DANGLING_LIMIT}, max {@value #MAX_DANGLING_LIMIT}), while the response's
     * {@code danglingCount}/{@code deferredCount} report the full unresolved totals for the scope.
     */
    @GetMapping("/graph")
    public ResponseEntity<?> graph(
            @RequestParam(required = false) String workspace,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer danglingLimit) {
        if (notWired()) {
            return unavailable();
        }
        boolean hasWs = workspace != null && !workspace.isBlank();
        boolean hasProj = project != null && !project.isBlank();
        if (hasWs != hasProj) {
            return ResponseEntity.badRequest().body(error(
                    "workspace and project must be given together (omit both for the cross-project graph)"));
        }
        Scope scope = hasWs ? Scope.of(workspace, project) : null;
        int lim = clampLimit(limit);
        int off = clampOffset(offset);
        int dLim = clampDangling(danglingLimit);

        GraphService gs = graphs.getObject();
        GraphQuery query = scope == null
                ? GraphQuery.crossProject(lim, off)
                : GraphQuery.scoped(scope, lim, off);
        DependencyGraph g = gs.graph(query);
        return ResponseEntity.ok(WebDtos.GraphView.of(
                scope, g, gs.danglingReport(scope, dLim), gs.unresolvedCounts(scope)));
    }

    // --- shared helpers ------------------------------------------------------------------------

    private WebDtos.SearchResult runSearch(Scope scope, String q, int limit) {
        RecallResult result = recall.getObject().search(new RecallQuery(q, scope, limit));
        return WebDtos.SearchResult.of(scope, result);
    }

    private WebDtos.BriefingView buildBriefing(Scope scope, int recentLimit) {
        McpReadRepository rr = reads.getObject();
        McpReadRepository.Counts c = rr.counts(scope);
        List<WebDtos.PageSummary> recent = pages.getObject()
                .listLatest(scope.workspace(), scope.project()).stream()
                .limit(recentLimit).map(WebDtos.PageSummary::of).toList();
        return WebDtos.BriefingView.of(
                scope, c,
                rr.observationsInLastDays(scope, 7),
                rr.observationsInLastDays(scope, 30),
                rr.latestPathsUnder(scope, "_rules/", 50),
                rr.latestPathsUnder(scope, "_slots/", 50),
                recent);
    }

    /** True when the DB-backed layer is not wired (DB-less smoke context). */
    private boolean notWired() {
        return recall.getIfAvailable() == null
                || pages.getIfAvailable() == null
                || reads.getIfAvailable() == null
                || webReads.getIfAvailable() == null
                || graphs.getIfAvailable() == null;
    }

    private static ResponseEntity<Object> unavailable() {
        return ResponseEntity.status(503).body(error("read store not configured"));
    }

    private static java.util.Map<String, Object> error(String message) {
        return java.util.Map.of("error", message);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static int clampOffset(Integer offset) {
        if (offset == null) {
            return 0;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        return offset;
    }

    private static int clampDangling(Integer limit) {
        if (limit == null) {
            return DEFAULT_DANGLING_LIMIT;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("danglingLimit must be > 0");
        }
        return Math.min(limit, MAX_DANGLING_LIMIT);
    }
}
