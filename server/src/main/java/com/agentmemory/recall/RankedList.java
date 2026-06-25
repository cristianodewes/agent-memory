package com.agentmemory.recall;

import java.util.List;

/**
 * One arm's contribution to fusion: an ordered list of candidate keys, best first. A "key" is a
 * stable identifier for a candidate (here, a page-version id string) so the same page produced by
 * two arms — full-text and link-graph — is recognized as one item and its rank contributions are
 * summed (see {@link RrfFusion}).
 *
 * <p>Reciprocal Rank Fusion deliberately consumes only the <em>order</em>, never the arms' raw
 * scores, so wildly different score scales (a {@code ts_rank} float vs. a graph in-degree count vs. a
 * future cosine distance) compose without normalization. This is what keeps the fusion pluggable:
 * #16 supplies another {@code RankedList} (vector) and #21 re-ranks the fused output, neither needing
 * to touch the existing arms.
 *
 * @param name the arm's name (e.g. {@code "fts"}, {@code "graph"}); for diagnostics/tests.
 * @param keys the candidate keys in rank order (index 0 = rank 1); never null, no duplicates expected.
 */
public record RankedList(String name, List<String> keys) {

    public RankedList {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ranked list name must not be blank");
        }
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}
