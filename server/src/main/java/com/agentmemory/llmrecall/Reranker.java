package com.agentmemory.llmrecall;

import com.agentmemory.recall.RecallHit;
import java.time.Duration;
import java.util.List;

/**
 * The recall re-rank seam (issue #130, Fase 2): re-order the RRF-fused candidates by relevance to the
 * query, reporting whether the scores it produced are <em>calibrated</em>. {@link LlmRecallService}
 * depends on this abstraction rather than a concrete reranker so the calibrated cross-encoder
 * ({@code CrossEncoderReranker}) can stand in front of the generative LLM reranker
 * ({@link CandidateReranker}) without the service learning which one ran.
 *
 * <p><strong>Never worse than baseline.</strong> Like every recall LLM step, an implementation must
 * degrade to the unchanged RRF input on any failure, timeout, or unavailability — so the returned hits
 * are never worse than the hybrid baseline.
 */
public interface Reranker {

    /**
     * Re-rank {@code fused} (the RRF-ordered hits, already best-first) by relevance to {@code queryText}.
     *
     * @param queryText      the user's recall text.
     * @param fused          the RRF-ordered hits; never null.
     * @param requestTimeout the per-call HTTP timeout from the remaining recall budget (issue #130), or
     *     {@code null} for the provider/client default.
     * @return the re-ordered hits plus whether their scores are calibrated; on any failure the hits are
     *     {@code fused} unchanged and {@link Result#calibrated()} is {@code false}.
     */
    Result rerank(String queryText, List<RecallHit> fused, Duration requestTimeout);

    /**
     * A re-rank outcome: the (possibly re-ordered) hits and whether their scores are calibrated.
     *
     * <p>{@code calibrated} is {@code true} only when a cross-encoder produced the scores — a true
     * relevance value in {@code [0, 1]} an absolute gate can threshold. The generative LLM reranker and
     * the raw RRF fallback are <em>not</em> calibrated (LLM self-reported relevance is not reliable for
     * an absolute cut, and RRF scores are tiny and uncalibrated), so they report {@code false} and only
     * the relative gate applies downstream ({@code RecallInjection}).
     *
     * @param hits       the re-ordered hits (or the unchanged input on degradation).
     * @param calibrated whether {@code hits}' scores are calibrated cross-encoder relevances.
     */
    record Result(List<RecallHit> hits, boolean calibrated) {

        public Result {
            if (hits == null) {
                throw new IllegalArgumentException("rerank result hits must not be null");
            }
        }

        /** @return a calibrated outcome (a cross-encoder scored the hits). */
        public static Result calibrated(List<RecallHit> hits) {
            return new Result(hits, true);
        }

        /** @return an uncalibrated outcome (LLM rerank or raw RRF order). */
        public static Result uncalibrated(List<RecallHit> hits) {
            return new Result(hits, false);
        }
    }
}
