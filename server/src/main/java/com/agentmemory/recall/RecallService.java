package com.agentmemory.recall;

/**
 * Hybrid recall over a project's memory (ARCHITECTURE §3.3, §5.1; issue #15): full-text + link-graph
 * neighborhood fused with Reciprocal Rank Fusion, with a bounded raw-observation fallback when no
 * compiled page matches. This is the base retrieval that #16 (vector arm) and #21 (LLM
 * query-expansion / re-rank) layer onto without reshaping the interface.
 */
public interface RecallService {

    /**
     * Run a hybrid recall query.
     *
     * @param query the search text, scope and limit; never null.
     * @return the fused compiled-page hits, or — when pages miss — the bounded, clearly-flagged
     *     raw-observation fallback; never null (possibly empty).
     */
    RecallResult search(RecallQuery query);
}
