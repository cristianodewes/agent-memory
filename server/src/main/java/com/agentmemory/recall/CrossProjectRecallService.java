package com.agentmemory.recall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cross-project recall (issue #29): fan the single-scope {@link RecallService} out over several named
 * {@code scopes} or every project ({@code global}) and merge the per-scope ranked hits into one list,
 * each annotated with the {@link Scope} it came from.
 *
 * <h2>Isolation</h2>
 * Only the requested scopes are searched (for {@code global}, every project with a latest page,
 * enumerated from the store). A hit therefore can never originate outside the requested set — isolation
 * is structural, not a post-filter (issue #29 acceptance).
 *
 * <h2>Merge</h2>
 * Each scope's result is already RRF-fused and capped. Because {@link RrfFusion} scores a hit purely
 * from its ordinal rank with a fixed constant (score = Σ 1/(k+rank)), the fused scores are directly
 * comparable across scopes — a rank-1 hit scores identically in every scope — so the cross-scope merge
 * is a stable sort by fused score descending (tie-break: scope order, then per-scope rank, then id),
 * capped at {@code limit}, with no re-fusion. Each surviving hit's {@code rank} is re-stamped to its
 * global 1-based position.
 *
 * <h2>Fallback</h2>
 * The bounded raw-observation fallback is per-scope and lower-confidence; it is surfaced only when
 * <em>no</em> scope produced a compiled-page hit, mirroring the single-scope {@link RecallResult}
 * contract. Compiled-page hits from any scope always win over another scope's raw fallback.
 *
 * <p>Stateless given its collaborators.
 */
public class CrossProjectRecallService {

    private final RecallService recall;
    private final RecallRepository repository; // enumerates all scopes for a global query

    public CrossProjectRecallService(RecallService recall, RecallRepository repository) {
        this.recall = recall;
        this.repository = repository;
    }

    /**
     * Recall across an explicit set of scopes, merged and globally ranked.
     *
     * @param text   the search text; never null/blank.
     * @param scopes the projects to search; never null/empty (duplicates are ignored, order kept).
     * @param limit  max merged hits to return; must be {@code > 0}.
     * @return the merged cross-project result, each hit tagged with its origin scope.
     */
    public CrossProjectRecallResult search(String text, List<Scope> scopes, int limit) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("cross-project query text must not be null or blank");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, was " + limit);
        }
        List<Scope> distinct = distinct(scopes);

        List<Ranked> pageHits = new ArrayList<>();
        List<Ranked> rawHits = new ArrayList<>();
        int scopeOrder = 0;
        for (Scope scope : distinct) {
            // Each scope runs the full single-scope hybrid pipeline (its own arms + fusion + fallback).
            RecallResult r = recall.search(new RecallQuery(text, scope, limit));
            List<Ranked> target = r.rawFallback() ? rawHits : pageHits;
            for (RecallHit h : r.hits()) {
                target.add(new Ranked(scopeOrder, scope, h));
            }
            scopeOrder++;
        }

        // Compiled-page hits win; the raw fallback is used only when no scope produced a page hit.
        boolean usingFallback = pageHits.isEmpty();
        List<Ranked> chosen = usingFallback ? rawHits : pageHits;
        chosen.sort(MERGE_ORDER);

        List<ScopedRecallHit> merged = new ArrayList<>(Math.min(limit, chosen.size()));
        int rank = 1;
        for (Ranked rk : chosen) {
            if (merged.size() >= limit) {
                break;
            }
            // Keep the fused score; re-stamp the rank to the global cross-scope position.
            RecallHit stamped = rk.hit().withRankAndScore(rank, rk.hit().score());
            merged.add(new ScopedRecallHit(
                    rk.scope().workspaceSlug(), rk.scope().projectSlug(), stamped));
            rank++;
        }
        return new CrossProjectRecallResult(distinct, usingFallback && !merged.isEmpty(), merged);
    }

    /**
     * Recall across <em>every</em> project in the store ({@code global = true}) — the scopes are
     * enumerated from the latest pages, so an empty store yields an empty result.
     *
     * @param text  the search text; never null/blank.
     * @param limit max merged hits to return; must be {@code > 0}.
     * @return the merged global result.
     */
    public CrossProjectRecallResult searchGlobal(String text, int limit) {
        List<Scope> all = repository.allScopes();
        if (all.isEmpty()) {
            return new CrossProjectRecallResult(List.of(), false, List.of());
        }
        return search(text, all, limit);
    }

    /** De-duplicate scopes while preserving first-seen order. */
    private static List<Scope> distinct(List<Scope> scopes) {
        List<Scope> out = new ArrayList<>(scopes.size());
        for (Scope s : scopes) {
            if (s != null && !out.contains(s)) {
                out.add(s);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("at least one non-null scope is required");
        }
        return out;
    }

    // Fused score desc; tie-break by scope order, then per-scope rank, then id — fully deterministic.
    private static final Comparator<Ranked> MERGE_ORDER =
            Comparator.comparingDouble((Ranked r) -> r.hit().score()).reversed()
                    .thenComparingInt(Ranked::scopeOrder)
                    .thenComparingInt(r -> r.hit().rank())
                    .thenComparing(r -> r.hit().id());

    /** A hit paired with the order and identity of the scope it came from. */
    private record Ranked(int scopeOrder, Scope scope, RecallHit hit) {}
}
