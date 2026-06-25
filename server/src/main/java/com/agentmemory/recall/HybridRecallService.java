package com.agentmemory.recall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link RecallService}: orchestrates the full-text, link-graph, and optional vector arms,
 * fuses them with a pluggable {@link Fusion} (RRF here), and falls back to bounded raw observations
 * when no compiled page matches (issue #15; vector arm added in #16; ARCHITECTURE §3.3).
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li><strong>Full-text</strong>: query {@code pages_fts} for the latest pages in scope, taking a
 *       pool of up to {@link #seedPoolSize} candidates (≥ the requested {@code limit}) so the graph
 *       arm has seeds to expand even when {@code limit} is small.</li>
 *   <li><strong>Link-graph</strong>: expand the FTS hits one hop over {@code links} (both
 *       directions) and rank neighbors by edge-count to the seed set.</li>
 *   <li><strong>Vector</strong> (optional, #16): embed the query and rank latest pages by cosine
 *       distance over {@code page_embeddings}. Present only when an embedder is configured; it
 *       contributes an empty arm (leaving the result identical to FTS + graph) when embeddings are
 *       unavailable, so recall never fails on it (DD-005).</li>
 *   <li><strong>Fuse</strong>: hand the ranked arms to the {@link Fusion}; they are merged into one
 *       ordered list by Reciprocal Rank Fusion, capped at {@code limit}. Because RRF consumes only
 *       ordinal rank, the arms' incomparable score scales ({@code ts_rank}, graph in-degree, cosine)
 *       fuse without normalization.</li>
 *   <li><strong>Fallback</strong>: if (and only if) the fused page result is empty, return up to
 *       {@code limit} raw-observation hits from {@code observations_fts}, flagged as a fallback.</li>
 * </ol>
 *
 * <p>The fusion is injected and the vector arm is an optional collaborator, so #16 contributes a
 * third {@link RankedList} and #21 can re-rank the fused output without reshaping this orchestration.
 * Stateless given its collaborators.
 */
public final class HybridRecallService implements RecallService {

    /** Names of the SQL arms this service contributes; surfaced in {@link RankedList} for tests. */
    static final String ARM_FTS = "fts";
    static final String ARM_GRAPH = "graph";

    private final RecallRepository repository;
    private final Fusion fusion;
    private final int seedPoolSize;
    private final VectorArm vectorArm; // nullable: the optional semantic arm (#16, DD-005)

    /**
     * @param repository the SQL arms.
     * @param fusion     the rank-fusion strategy (RRF in production).
     */
    public HybridRecallService(RecallRepository repository, Fusion fusion) {
        this(repository, fusion, 50, null);
    }

    /**
     * @param repository the SQL arms.
     * @param fusion     the rank-fusion strategy.
     * @param vectorArm  the optional vector (semantic) arm (#16), or {@code null} for FTS + graph only.
     */
    public HybridRecallService(RecallRepository repository, Fusion fusion, VectorArm vectorArm) {
        this(repository, fusion, 50, vectorArm);
    }

    /**
     * @param repository   the SQL arms.
     * @param fusion       the rank-fusion strategy.
     * @param seedPoolSize how many FTS candidates to retrieve as the pool fed to the graph arm and
     *     fusion (clamped to at least the query limit); must be {@code > 0}.
     * @param vectorArm    the optional vector (semantic) arm (#16), or {@code null} to disable it; a
     *     present-but-degraded arm contributes an empty {@link RankedList} and recall is unaffected.
     */
    public HybridRecallService(
            RecallRepository repository, Fusion fusion, int seedPoolSize, VectorArm vectorArm) {
        if (seedPoolSize <= 0) {
            throw new IllegalArgumentException("seedPoolSize must be > 0, was " + seedPoolSize);
        }
        this.repository = repository;
        this.fusion = fusion;
        this.seedPoolSize = seedPoolSize;
        this.vectorArm = vectorArm;
    }

    @Override
    public RecallResult search(RecallQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("recall query must not be null");
        }
        String ws = query.scope().workspaceSlug();
        String proj = query.scope().projectSlug();
        String text = query.text();
        int pool = Math.max(seedPoolSize, query.limit());

        // 1. Full-text arm.
        List<Candidate> ftsCandidates = repository.ftsPages(ws, proj, text, pool);

        // 2. Link-graph arm, seeded by the FTS hits.
        List<String> seedIds = new ArrayList<>(ftsCandidates.size());
        for (Candidate c : ftsCandidates) {
            seedIds.add(c.key());
        }
        List<Candidate> graphCandidates = repository.graphNeighbors(ws, proj, text, seedIds, pool);

        // 3. Build the displayable-hit map (first writer wins: an FTS hit's payload is kept even if
        // the page also appears as a graph neighbor) and the ranked arms for fusion.
        Map<String, RecallHit> hitsByKey = new LinkedHashMap<>();
        RankedList ftsArm = toArm(ARM_FTS, ftsCandidates, hitsByKey);
        RankedList graphArm = toArm(ARM_GRAPH, graphCandidates, hitsByKey);

        // Arms fed to the fusion. FTS + graph are always present; the optional vector arm (#16) adds
        // a third ranked list when embeddings are available, and contributes an empty list (leaving
        // the result identical to FTS + graph) when it is unavailable — recall never fails on it.
        List<RankedList> arms = new ArrayList<>(3);
        arms.add(ftsArm);
        arms.add(graphArm);
        if (vectorArm != null) {
            // The vector arm seeds nothing downstream (only the FTS arm over-fetches a pool to feed
            // the graph), so it fetches just the requested limit rather than the larger seed pool.
            VectorArm.Result vector =
                    vectorArm.rank(ws, proj, text, query.limit()); // never throws; empty when degraded
            // The vector arm registers only ids the SQL arms did not already supply (a page found by
            // FTS keeps its richer snippet/title — first writer wins), so a vector-only hit is still
            // navigable while a shared hit keeps its better payload.
            for (Candidate c : vector.candidates()) {
                hitsByKey.putIfAbsent(c.key(), c.hit());
            }
            arms.add(vector.ranked());
        }

        List<RecallHit> fused = fusion.fuse(arms, hitsByKey, query.limit());
        if (!fused.isEmpty()) {
            return RecallResult.ofPages(fused);
        }

        // 4. Bounded raw-observation fallback — only when compiled pages missed entirely.
        List<Candidate> raw = repository.rawObservations(ws, proj, text, query.limit());
        if (raw.isEmpty()) {
            return RecallResult.empty();
        }
        List<RecallHit> rawHits = new ArrayList<>(raw.size());
        int rank = 1;
        for (Candidate c : raw) {
            // The fallback is not fused; rank by the SQL order and expose a descending positional
            // score so callers have a monotonic value to sort/threshold on.
            rawHits.add(c.hit().withRankAndScore(rank, 1.0 / rank));
            rank++;
        }
        return RecallResult.ofRawFallback(rawHits);
    }

    /**
     * Convert ranked candidates into a {@link RankedList} of keys (preserving the arm's order) while
     * registering each candidate's displayable hit in {@code hitsByKey} without clobbering an entry
     * an earlier arm already supplied.
     */
    private static RankedList toArm(
            String name, List<Candidate> candidates, Map<String, RecallHit> hitsByKey) {
        List<String> keys = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            keys.add(c.key());
            hitsByKey.putIfAbsent(c.key(), c.hit());
        }
        return new RankedList(name, keys);
    }
}
