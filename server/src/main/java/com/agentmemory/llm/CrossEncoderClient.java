package com.agentmemory.llm;

import java.time.Duration;
import java.util.List;

/**
 * A non-generative cross-encoder reranker: given a query and a list of documents, it returns a
 * <em>calibrated</em> relevance score in {@code [0, 1]} for <strong>every</strong> document, aligned
 * to the input order (issue #130, Fase 2). Unlike the generative LLM reranker
 * ({@code CandidateReranker}) this never echoes ids and always covers all inputs, so it has no
 * full-coverage fragility — and its score is calibrated enough to drive an absolute relevance gate.
 *
 * <p>The seam exists so the cross-encoder backend (Voyage {@code rerank-2-lite},
 * {@link VoyageReranker}) can be swapped or stubbed in hermetic tests without a network call, exactly
 * as {@link Embedder} abstracts the embeddings backend.
 */
public interface CrossEncoderClient {

    /**
     * Score each document by relevance to {@code query}.
     *
     * @param query          the recall query text.
     * @param documents      the candidate document texts; non-empty, each non-blank.
     * @param requestTimeout a per-call HTTP timeout (the remaining recall budget, issue #130), or
     *     {@code null} for the client default.
     * @return a calibrated relevance score in {@code [0, 1]} per document, in the same order as
     *     {@code documents} (length equals {@code documents.size()}).
     * @throws LlmException on transport failure, a non-2xx status, or an incomplete response.
     */
    double[] scoreDocuments(String query, List<String> documents, Duration requestTimeout);
}
