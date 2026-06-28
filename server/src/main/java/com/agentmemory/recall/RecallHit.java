package com.agentmemory.recall;

import com.agentmemory.core.MemoryLayer;
import java.time.Instant;

/**
 * One ranked recall result, carrying enough for a caller / the read API / the UI to display and
 * navigate it (issue #15 acceptance: path, score/rank, snippet), plus the recency/layer signals the
 * recency prior and the injected block render (issue #140).
 *
 * <p>For a {@link HitSource#PAGE} hit, {@code path} is the page's project-relative path and
 * {@code title} its title. For a {@link HitSource#RAW_OBSERVATION} fallback hit there is no page, so
 * {@code path} is {@code null}, {@code title} is a synthetic label (the observation kind), and
 * {@code id} is the observation's id; {@code kind} carries the canonical observation kind. The
 * {@code snippet} is an HTML-marked excerpt (matches wrapped in {@code <mark>…</mark>}) suitable for
 * direct display.
 *
 * <p><strong>Recency metadata (issue #140).</strong> {@code updatedAt} is the page version's
 * {@code pages.updated_at} and {@code layer} its {@link MemoryLayer} — together they drive the
 * per-layer recency decay applied in the {@code recall} fusion (see {@link RecencyDecayFusion}) and
 * the layer/recency annotations the injected block renders. Both are {@code null} where they do not
 * apply: a {@link HitSource#RAW_OBSERVATION} fallback hit, and a vector-only hit whose richer payload
 * the SQL arms did not supply (those layers/timestamps are not loaded by the vector query).
 *
 * @param source    which arm produced this hit.
 * @param id        the page-version id (PAGE) or observation id (RAW_OBSERVATION), canonical UUID text.
 * @param path      project-relative page path for a PAGE hit; {@code null} for a raw-observation hit.
 * @param title     page title (PAGE) or a synthetic label (RAW_OBSERVATION).
 * @param kind      the observation kind for a RAW_OBSERVATION hit; {@code null} for a PAGE hit.
 * @param score     the fused relevance score (RRF, optionally recency-decayed and/or cross-encoder
 *     re-scored) — higher is better; not comparable across queries.
 * @param rank      the 1-based position of this hit in the returned list.
 * @param snippet   an HTML-marked excerpt for display.
 * @param updatedAt the page version's last-update instant ({@code pages.updated_at}); {@code null}
 *     when unknown (raw-observation fallback, vector-only hit).
 * @param layer     the page's retention {@link MemoryLayer}; {@code null} when unknown (as above).
 */
public record RecallHit(
        HitSource source,
        String id,
        String path,
        String title,
        String kind,
        double score,
        int rank,
        String snippet,
        Instant updatedAt,
        MemoryLayer layer) {

    /**
     * Convenience constructor for a hit with no recency metadata — the raw-observation fallback, a
     * vector-only hit, and unit-test fixtures. {@code updatedAt} and {@code layer} default to
     * {@code null}, so the recency prior treats the hit as undatable (no decay) and the renderer
     * omits the layer/recency annotations.
     */
    public RecallHit(
            HitSource source,
            String id,
            String path,
            String title,
            String kind,
            double score,
            int rank,
            String snippet) {
        this(source, id, path, title, kind, score, rank, snippet, null, null);
    }

    /**
     * Returns a copy with {@code rank} and {@code score} set — used by the fusion (and the rerankers)
     * to stamp the final ordinal position and score onto a hit assembled from a ranker. The recency
     * metadata ({@code updatedAt}/{@code layer}) is preserved so it survives fusion, recency decay, and
     * any downstream re-rank all the way to the injected block.
     *
     * @param rank  the 1-based final position.
     * @param score the fused (or re-scored) value.
     * @return a copy carrying the given rank/score and the same recency metadata.
     */
    public RecallHit withRankAndScore(int rank, double score) {
        return new RecallHit(source, id, path, title, kind, score, rank, snippet, updatedAt, layer);
    }
}
