package com.agentmemory.recall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion (Cormack et al., 2009) of the recall arms. Each arm contributes, for every
 * candidate it ranks, a score of {@code 1 / (k + rank)} (rank is 1-based); a candidate's fused score
 * is the sum across arms. An item ranked highly by several arms therefore outscores one ranked highly
 * by only a single arm, and — because only ordinal rank is used — arms with incomparable score scales
 * (full-text {@code ts_rank}, graph in-degree, a future vector cosine) fuse without normalization.
 *
 * <p><strong>Determinism / stable tie-break (issue #15 acceptance).</strong> Ties on the fused score
 * are broken by candidate key ascending, so the same arms always yield byte-identical ordering. The
 * accumulation uses a {@link LinkedHashMap} keyed by first-seen order, but the final sort makes the
 * output independent of arm/iteration order; the tie-break key removes the last source of nondeterminism.
 *
 * <p>{@code k} defaults to {@value #DEFAULT_K}, the value from the original paper that damps the
 * influence of very high ranks; it is configurable for experimentation. Stateless and thread-safe.
 * Published as a bean by {@link RecallConfiguration} (no DB dependency); a future arm or re-ranker
 * can swap the {@link Fusion} implementation without touching the service.
 */
public final class RrfFusion implements Fusion {

    /** The canonical RRF constant from Cormack et al. (2009). */
    public static final int DEFAULT_K = 60;

    private final int k;

    public RrfFusion() {
        this(DEFAULT_K);
    }

    /**
     * @param k the RRF damping constant; must be {@code > 0} (a larger {@code k} flattens the
     *     contribution of top ranks).
     */
    public RrfFusion(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k must be > 0, was " + k);
        }
        this.k = k;
    }

    @Override
    public List<RecallHit> fuse(List<RankedList> arms, Map<String, RecallHit> hitsByKey, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("fuse limit must be > 0, was " + limit);
        }
        // Accumulate the summed reciprocal-rank score per candidate key.
        Map<String, Double> scores = new LinkedHashMap<>();
        for (RankedList arm : arms) {
            List<String> keys = arm.keys();
            for (int i = 0; i < keys.size(); i++) {
                int rank = i + 1; // 1-based
                double contribution = 1.0 / (k + rank);
                scores.merge(keys.get(i), contribution, Double::sum);
            }
        }

        // Order by fused score desc, then key asc for a deterministic, stable tie-break.
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(scores.entrySet());
        ordered.sort(
                Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey));

        List<RecallHit> fused = new ArrayList<>(Math.min(limit, ordered.size()));
        int rank = 1;
        for (Map.Entry<String, Double> e : ordered) {
            if (fused.size() >= limit) {
                break;
            }
            RecallHit hit = hitsByKey.get(e.getKey());
            if (hit == null) {
                continue; // a key with no displayable payload (shouldn't happen) is skipped
            }
            fused.add(hit.withRankAndScore(rank, e.getValue()));
            rank++;
        }
        return fused;
    }

    /** @return the RRF damping constant in use. */
    public int k() {
        return k;
    }
}
