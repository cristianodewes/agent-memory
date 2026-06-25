package com.agentmemory.mcp;

import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import java.util.List;

/**
 * The flat, explicit JSON shapes the MCP read tools return (issue #17). Kept separate from the
 * domain/recall records on purpose: the tool wire format is a stable contract for agents and should
 * not move when an internal record changes, and these plain records serialize cleanly with the bare
 * Jackson 3 mapper (no Jackson annotations / typed value-wrappers to special-case).
 */
final class McpDtos {

    private McpDtos() {}

    /** The resolved scope echoed back so a caller can see which project answered. */
    record ScopeView(String workspace, String project) {
        static ScopeView of(Scope s) {
            return new ScopeView(s.workspaceSlug(), s.projectSlug());
        }
    }

    /** One hit in a {@code memory_query} response. */
    record QueryHit(
            String source, String id, String path, String title, String kind,
            double score, int rank, String snippet) {
        static QueryHit of(RecallHit h) {
            return new QueryHit(
                    h.source().name(), h.id(), h.path(), h.title(), h.kind(),
                    h.score(), h.rank(), h.snippet());
        }
    }

    /** {@code memory_query} result: the scope, whether the raw fallback fired, and the hits. */
    record QueryResult(ScopeView scope, boolean rawFallback, int count, List<QueryHit> hits) {
        static QueryResult of(Scope scope, RecallResult r) {
            return new QueryResult(
                    ScopeView.of(scope), r.rawFallback(), r.hits().size(),
                    r.hits().stream().map(QueryHit::of).toList());
        }
    }

    /** One page entry in {@code memory_recent} (metadata only — no body). */
    record RecentPage(String path, String title, String updatedAt) {
        static RecentPage of(PageRecord p) {
            return new RecentPage(
                    p.page().path().value(), p.page().title(), p.page().updatedAt().toString());
        }
    }

    /** {@code memory_recent} result. */
    record RecentResult(ScopeView scope, int count, List<RecentPage> pages) {}

    /** {@code memory_read_page} result: the full page body plus identity/version metadata. */
    record PageView(
            String path, String title, String body, boolean latest, String updatedAt,
            boolean matchedByQuery) {
        static PageView of(PageRecord p, boolean matchedByQuery) {
            return new PageView(
                    p.page().path().value(), p.page().title(), p.page().body(), p.isLatest(),
                    p.page().updatedAt().toString(), matchedByQuery);
        }
    }

    /** {@code memory_status} result: lifetime counts + the resolved scope. */
    record StatusResult(
            ScopeView scope, long pages, long observations, long sessions, long links) {}

    /**
     * {@code memory_briefing} result: a structured snapshot (no LLM) — counts, recent activity
     * windows, the {@code _rules/}/{@code _slots/} listings, and recent page paths.
     */
    record BriefingResult(
            ScopeView scope,
            long pages,
            long observations,
            long sessions,
            long links,
            long observationsLast7Days,
            long observationsLast30Days,
            List<String> rules,
            List<String> slots,
            List<RecentPage> recent) {}
}
