package com.agentmemory.recall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A {@link Fusion} decorator that applies the per-layer recency prior (issue #140) on top of a base
 * fusion (RRF in production): it multiplies each fused hit's score by its {@link RecencyDecay} factor
 * and re-orders the result, so that — all else equal — a more recently updated page outranks a stale
 * one, while {@link com.agentmemory.core.MemoryLayer#SEMANTIC} / procedural pages are left undecayed.
 *
 * <h2>Why decorate (and rank the whole pool)</h2>
 * The decay must be able to lift a fresh candidate from <em>below</em> the requested page into it, not
 * merely reshuffle the top {@code limit}. So the decorator asks the delegate to fuse the entire
 * candidate pool ({@code max(limit, |hitsByKey|)}), applies the recency factor to every fused hit,
 * re-sorts by the decayed score, and only then caps to {@code limit}. Because RRF already sorts all
 * candidates regardless of the cap, fusing the whole pool adds no meaningful cost.
 *
 * <h2>Placement: pre-rerank</h2>
 * As the {@code recall}-package {@link Fusion}, this runs inside the base hybrid search, i.e.
 * <em>before</em> the Fase-2 cross-encoder re-rank that {@code LlmRecallService} layers on top. The
 * prior therefore shapes the candidate pool and the non-LLM fast-path order; when the cross-encoder
 * runs it re-scores the decayed-ordered head by pure relevance (recency and relevance compose: pool by
 * recency+relevance → top-K → rerank). It does not corrupt a calibrated relevance score — the
 * cross-encoder overwrites the decayed score with its own.
 *
 * <h2>Determinism</h2>
 * Ties on the decayed score are broken by hit id ascending — the same stable tie-break {@link RrfFusion}
 * uses on the fused score — so the ordering is byte-identical across runs.
 *
 * <p>Stateless given its collaborators; thread-safe.
 */
public final class RecencyDecayFusion implements Fusion {

    private final Fusion delegate;
    private final RecencyDecay decay;

    /**
     * @param delegate the base fusion to decorate (e.g. {@link RrfFusion}); never null.
     * @param decay    the recency prior; never null.
     */
    public RecencyDecayFusion(Fusion delegate, RecencyDecay decay) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate fusion must not be null");
        }
        if (decay == null) {
            throw new IllegalArgumentException("recency decay must not be null");
        }
        this.delegate = delegate;
        this.decay = decay;
    }

    @Override
    public List<RecallHit> fuse(List<RankedList> arms, Map<String, RecallHit> hitsByKey, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("fuse limit must be > 0, was " + limit);
        }
        // Fuse the WHOLE pool (not just the top `limit`) so the recency prior can pull a fresher
        // candidate up from beyond the requested page before we cap.
        int rankAll = Math.max(limit, hitsByKey.size());
        List<RecallHit> fused = delegate.fuse(arms, hitsByKey, rankAll);

        // Attenuate each fused score by its per-layer recency factor (1.0 = undecayed).
        List<RecallHit> decayed = new ArrayList<>(fused.size());
        for (RecallHit h : fused) {
            double factor = decay.factor(h.layer(), h.updatedAt());
            // factor == 1.0 leaves the score (and its withRankAndScore identity) effectively unchanged.
            decayed.add(h.withRankAndScore(h.rank(), h.score() * factor));
        }

        // Re-order by the decayed score desc, id asc (RRF's stable tie-break), then re-stamp contiguous
        // ranks and cap to the caller's limit.
        decayed.sort(Comparator.comparingDouble(RecallHit::score).reversed()
                .thenComparing(RecallHit::id));

        List<RecallHit> out = new ArrayList<>(Math.min(limit, decayed.size()));
        int rank = 1;
        for (RecallHit h : decayed) {
            if (out.size() >= limit) {
                break;
            }
            out.add(h.withRankAndScore(rank, h.score()));
            rank++;
        }
        return out;
    }
}
