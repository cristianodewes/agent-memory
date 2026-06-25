package com.agentmemory.recall;

/**
 * One ranked recall result, carrying enough for a caller / the read API / the UI to display and
 * navigate it (issue #15 acceptance: path, score/rank, snippet).
 *
 * <p>For a {@link HitSource#PAGE} hit, {@code path} is the page's project-relative path and
 * {@code title} its title. For a {@link HitSource#RAW_OBSERVATION} fallback hit there is no page, so
 * {@code path} is {@code null}, {@code title} is a synthetic label (the observation kind), and
 * {@code id} is the observation's id; {@code kind} carries the canonical observation kind. The
 * {@code snippet} is an HTML-marked excerpt (matches wrapped in {@code <mark>…</mark>}) suitable for
 * direct display.
 *
 * @param source  which arm produced this hit.
 * @param id      the page-version id (PAGE) or observation id (RAW_OBSERVATION), canonical UUID text.
 * @param path    project-relative page path for a PAGE hit; {@code null} for a raw-observation hit.
 * @param title   page title (PAGE) or a synthetic label (RAW_OBSERVATION).
 * @param kind    the observation kind for a RAW_OBSERVATION hit; {@code null} for a PAGE hit.
 * @param score   the fused relevance score (RRF) — higher is better; not comparable across queries.
 * @param rank    the 1-based position of this hit in the returned list.
 * @param snippet an HTML-marked excerpt for display.
 */
public record RecallHit(
        HitSource source,
        String id,
        String path,
        String title,
        String kind,
        double score,
        int rank,
        String snippet) {

    /**
     * Returns a copy with {@code rank} and {@code score} set — used by the fusion to stamp the final
     * ordinal position and fused score onto a hit assembled from a ranker.
     *
     * @param rank  the 1-based final position.
     * @param score the fused score.
     * @return a copy carrying the given rank/score.
     */
    public RecallHit withRankAndScore(int rank, double score) {
        return new RecallHit(source, id, path, title, kind, score, rank, snippet);
    }
}
