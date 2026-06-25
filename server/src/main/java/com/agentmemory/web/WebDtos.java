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

    /** A page summary (metadata only, no body) for listings and {@code recent}. */
    public record PageSummary(
            String path, String title, boolean latest,
            long accessCount, String createdAt, String updatedAt) {
        static PageSummary of(PageRecord p) {
            return new PageSummary(
                    p.page().path().value(),
                    p.page().title(),
                    p.isLatest(),
                    p.accessCount(),
                    p.page().createdAt().toString(),
                    p.page().updatedAt().toString());
        }
    }

    /** A page with its full body for {@code GET .../pages/{path}}. */
    public record PageDetail(
            String path, String title, String body, boolean latest,
            long accessCount, String createdAt, String updatedAt) {
        static PageDetail of(PageRecord p) {
            return new PageDetail(
                    p.page().path().value(),
                    p.page().title(),
                    p.page().body(),
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
                    last7, last30, rules, slots, recent);
        }
    }

    /** Memory-health rollup for {@code overview} (lifetime counts + recent activity). */
    public record HealthView(
            long pages, long observations, long sessions, long links,
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

    /**
     * The dependency graph for {@code GET /api/v1/graph} — a documented seam for #28. Until the
     * unified graph endpoint is implemented the API returns an empty, well-typed graph (not a 404) so
     * a frontend can render "no graph yet" rather than handle a missing route.
     *
     * @param nodes the graph nodes (empty until #28).
     * @param edges the graph edges (empty until #28).
     * @param note  a human-readable marker that the graph endpoint is a pending seam (#28).
     */
    public record GraphView(List<Object> nodes, List<Object> edges, String note) {
        static GraphView pendingSeam() {
            return new GraphView(List.of(), List.of(), "graph endpoint pending (#28)");
        }
    }
}
