package com.agentmemory.recall;

import java.util.List;

/**
 * The outcome of a {@link RecallQuery}: the ranked {@link RecallHit}s plus a flag marking whether the
 * results are the bounded raw-observation fallback (compiled pages missed) rather than compiled-page
 * hits. The flag lets a caller render the lower-confidence fallback distinctly (issue #15: raw hits
 * "clearly labeled"). When pages matched, {@code rawFallback} is {@code false} and every hit is a
 * {@link HitSource#PAGE}; when it is {@code true}, every hit is a {@link HitSource#RAW_OBSERVATION}.
 *
 * <p>{@code calibrated} (issue #130, Fase 2) marks whether the hit scores came from a calibrated
 * cross-encoder rerank — a true relevance value in {@code [0, 1]} an absolute gate can threshold.
 * Uncalibrated results (raw RRF fusion, the LLM reranker, or the raw-observation fallback) report
 * {@code false}, so the injection layer applies only the scale-invariant relative gate to them rather
 * than wrongly emptying a block whose tiny RRF scores all sit below an absolute threshold.
 *
 * @param hits        the ranked hits (already ordered by rank; possibly empty when nothing matched).
 * @param rawFallback {@code true} iff these are the raw-observation fallback hits.
 * @param calibrated  {@code true} iff the hit scores are calibrated cross-encoder relevances.
 */
public record RecallResult(List<RecallHit> hits, boolean rawFallback, boolean calibrated) {

    public RecallResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /** @return a result wrapping fused compiled-page hits (not a fallback, uncalibrated scores). */
    public static RecallResult ofPages(List<RecallHit> hits) {
        return new RecallResult(hits, false, false);
    }

    /**
     * @param calibrated whether {@code hits}' scores are calibrated cross-encoder relevances.
     * @return a result wrapping fused compiled-page hits (not a fallback).
     */
    public static RecallResult ofPages(List<RecallHit> hits, boolean calibrated) {
        return new RecallResult(hits, false, calibrated);
    }

    /** @return a result wrapping the bounded raw-observation fallback hits (uncalibrated). */
    public static RecallResult ofRawFallback(List<RecallHit> hits) {
        return new RecallResult(hits, true, false);
    }

    /** @return an empty, non-fallback result (nothing matched anywhere). */
    public static RecallResult empty() {
        return new RecallResult(List.of(), false, false);
    }

    /** @return {@code true} when there are no hits at all. */
    public boolean isEmpty() {
        return hits.isEmpty();
    }
}
