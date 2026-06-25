package com.agentmemory.mcp;

import com.agentmemory.recall.CrossProjectRecallResult;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.Scope;
import com.agentmemory.recall.ScopedRecallHit;
import com.agentmemory.store.PageRecord;
import com.agentmemory.wiki.SlotsReader;
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

    /**
     * One hit in a cross-project {@code memory_query} response (#29): a {@link QueryHit} annotated with
     * the {@code workspace} + {@code project} it came from, so a caller searching several projects can
     * tell each hit's origin.
     */
    record ScopedQueryHit(
            String workspace, String project,
            String source, String id, String path, String title, String kind,
            double score, int rank, String snippet) {
        static ScopedQueryHit of(ScopedRecallHit s) {
            RecallHit h = s.hit();
            return new ScopedQueryHit(
                    s.workspace(), s.project(),
                    h.source().name(), h.id(), h.path(), h.title(), h.kind(),
                    h.score(), h.rank(), h.snippet());
        }
    }

    /**
     * Cross-project {@code memory_query} result (#29): the scopes searched ({@code global} = every
     * project), whether the raw fallback fired, and the merged, globally-ranked scope-annotated hits.
     */
    record CrossQueryResult(
            List<ScopeView> scopes, boolean global, boolean rawFallback, int count,
            List<ScopedQueryHit> hits) {
        static CrossQueryResult of(CrossProjectRecallResult r) {
            return build(r, false);
        }

        static CrossQueryResult global(CrossProjectRecallResult r) {
            return build(r, true);
        }

        private static CrossQueryResult build(CrossProjectRecallResult r, boolean global) {
            return new CrossQueryResult(
                    r.scopes().stream().map(ScopeView::of).toList(),
                    global, r.rawFallback(), r.hits().size(),
                    r.hits().stream().map(ScopedQueryHit::of).toList());
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
     * One memory slot in a briefing: an auto-pinned {@code _slots/} page with its declared write
     * regime ({@code slot_kind} — {@code state} or {@code invariant}).
     */
    record SlotView(String path, String title, String slotKind, boolean pinned) {
        static SlotView of(SlotsReader.SlotView s) {
            return new SlotView(s.path(), s.title(), s.slotKind(), s.pinned());
        }
    }

    /**
     * {@code memory_briefing} result: a structured snapshot (no LLM) — counts, recent activity
     * windows, the {@code _rules/} listing, the memory {@code slots} (with their {@code slot_kind}),
     * and recent pages.
     */
    record BriefingResult(
            ScopeView scope,
            long pages,
            long observations,
            long sessions,
            long links,
            long dependents,
            long observationsLast7Days,
            long observationsLast30Days,
            List<String> rules,
            List<SlotView> slots,
            List<RecentPage> recent) {}

    /**
     * {@code memory_install_self_routing} result: the canonical routing snippet to paste into the
     * agent's project instructions, plus the marker fences (so an installer can replace it in place)
     * and the target-file hint.
     */
    record SelfRoutingResult(String snippet, String beginMarker, String endMarker, String target) {}
}
