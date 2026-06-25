package com.agentmemory.recall;

import java.util.List;

/**
 * The outcome of a {@link RecallQuery}: the ranked {@link RecallHit}s plus a flag marking whether the
 * results are the bounded raw-observation fallback (compiled pages missed) rather than compiled-page
 * hits. The flag lets a caller render the lower-confidence fallback distinctly (issue #15: raw hits
 * "clearly labeled"). When pages matched, {@code rawFallback} is {@code false} and every hit is a
 * {@link HitSource#PAGE}; when it is {@code true}, every hit is a {@link HitSource#RAW_OBSERVATION}.
 *
 * @param hits        the ranked hits (already ordered by rank; possibly empty when nothing matched).
 * @param rawFallback {@code true} iff these are the raw-observation fallback hits.
 */
public record RecallResult(List<RecallHit> hits, boolean rawFallback) {

    public RecallResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /** @return a result wrapping fused compiled-page hits (not a fallback). */
    public static RecallResult ofPages(List<RecallHit> hits) {
        return new RecallResult(hits, false);
    }

    /** @return a result wrapping the bounded raw-observation fallback hits. */
    public static RecallResult ofRawFallback(List<RecallHit> hits) {
        return new RecallResult(hits, true);
    }

    /** @return an empty, non-fallback result (nothing matched anywhere). */
    public static RecallResult empty() {
        return new RecallResult(List.of(), false);
    }

    /** @return {@code true} when there are no hits at all. */
    public boolean isEmpty() {
        return hits.isEmpty();
    }
}
