package com.agentmemory.recall;

import java.util.List;
import java.util.Map;

/**
 * Combines several ranked arms ({@link RankedList}s) into a single ordered result. The seam that
 * keeps recall extensible: #15 ships {@link RrfFusion} over the full-text and link-graph arms, #16
 * adds a vector {@code RankedList} to the same call, and #21 wraps/replaces the fusion to LLM
 * re-rank — none of which requires reshaping the arms or the service.
 */
public interface Fusion {

    /**
     * Fuse the given arms into one ranked list of hits, longest-supported first, capped at
     * {@code limit}.
     *
     * @param arms     the ranked arms to combine (each an ordered list of candidate keys).
     * @param hitsByKey the displayable {@link RecallHit} for every key referenced by the arms; the
     *     fusion stamps the final rank/score onto these. A key missing here is skipped.
     * @param limit    maximum number of fused hits to return; must be {@code > 0}.
     * @return the fused hits, ordered best-first, each carrying its final 1-based rank and score.
     */
    List<RecallHit> fuse(List<RankedList> arms, Map<String, RecallHit> hitsByKey, int limit);
}
