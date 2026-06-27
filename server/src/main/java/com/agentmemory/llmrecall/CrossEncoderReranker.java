package com.agentmemory.llmrecall;

import com.agentmemory.llm.CrossEncoderClient;
import com.agentmemory.recall.RecallHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Reranker} that puts a calibrated cross-encoder ({@link CrossEncoderClient}, Voyage
 * {@code rerank-2-lite}) in front of the generative LLM reranker (issue #130, Fase 2), with graceful
 * fallback that preserves "never worse than baseline":
 *
 * <ol>
 *   <li><strong>Cross-encoder (primary)</strong>: when a {@link CrossEncoderClient} is configured, score
 *       every candidate in the rerank head and re-order by that calibrated score. The result is reported
 *       {@link Reranker.Result#calibrated() calibrated} so the injection layer may apply an absolute
 *       gate.</li>
 *   <li><strong>LLM reranker (fallback)</strong>: on the cross-encoder being absent, erroring, or timing
 *       out, delegate to {@link CandidateReranker}. Its output is <em>uncalibrated</em> (LLM
 *       self-reported relevance is not reliable for an absolute cut).</li>
 *   <li><strong>Raw RRF (floor)</strong>: {@link CandidateReranker} itself returns the unchanged RRF
 *       order on any failure, so the worst case is still the hybrid baseline.</li>
 * </ol>
 *
 * <p><strong>K right-sizing (issue #130).</strong> The cross-encoder is cheap (~50-150ms), so its head is
 * the whole over-fetched candidate pool — bounded by {@code maxDocuments} — rather than the small LLM cap
 * K. Because {@link LlmRecallService} over-fetches {@code max(query.limit, maxCandidates)} candidates, an
 * MCP {@code memory_query(limit=20)} reranks all 20 while {@code /recall/inject} (limit 5) stays cheap.
 * Any tail beyond {@code maxDocuments} keeps its RRF order and score (only its rank is re-stamped), so a
 * downstream gate still sees its relevance signal.
 *
 * <p>The degradation mirrors {@link com.agentmemory.recall.VectorArm}: a one-time warning is logged when
 * the cross-encoder first degrades, and recall continues on the fallback. Pure given its collaborators;
 * unit-testable with a stubbed {@link CrossEncoderClient} and a scripted LLM reranker.
 */
public final class CrossEncoderReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(CrossEncoderReranker.class);

    /** Defensive per-snippet cap (chars) so one long snippet cannot blow up the rerank document. */
    private static final int MAX_SNIPPET_CHARS = 280;

    private final CrossEncoderClient crossEncoder; // nullable: absent when Voyage rerank is not configured
    private final CandidateReranker llmFallback;
    private final int maxDocuments;

    private final AtomicBoolean warnedDegraded = new AtomicBoolean(false);

    /**
     * @param crossEncoder the calibrated cross-encoder client, or {@code null} when it is not configured
     *     (then this is the LLM reranker behind a calibrated-aware seam).
     * @param llmFallback  the generative LLM reranker used when the cross-encoder is absent or fails.
     * @param maxDocuments the hard cap on how many candidates the cross-encoder scores in one call; must
     *     be {@code > 0}.
     */
    public CrossEncoderReranker(CrossEncoderClient crossEncoder, CandidateReranker llmFallback,
            int maxDocuments) {
        if (llmFallback == null) {
            throw new IllegalArgumentException("llmFallback must not be null");
        }
        if (maxDocuments <= 0) {
            throw new IllegalArgumentException("maxDocuments must be > 0, was " + maxDocuments);
        }
        this.crossEncoder = crossEncoder;
        this.llmFallback = llmFallback;
        this.maxDocuments = maxDocuments;
    }

    @Override
    public Result rerank(String queryText, List<RecallHit> fused, Duration requestTimeout) {
        if (fused == null || fused.size() <= 1) {
            return Result.uncalibrated(fused);
        }
        if (crossEncoder != null) {
            Result calibrated = tryCrossEncoder(queryText, fused, requestTimeout);
            if (calibrated != null) {
                return calibrated;
            }
            // else: the cross-encoder degraded; fall through to the LLM reranker.
        }
        return Result.uncalibrated(llmFallback.rerank(queryText, fused, requestTimeout));
    }

    /**
     * Run the cross-encoder over the head of {@code fused}, returning the calibrated re-order, or
     * {@code null} on any failure (so the caller degrades to the LLM reranker). Never throws.
     */
    private Result tryCrossEncoder(String queryText, List<RecallHit> fused, Duration requestTimeout) {
        int headSize = Math.min(maxDocuments, fused.size());
        List<RecallHit> head = fused.subList(0, headSize);
        List<RecallHit> tail = fused.subList(headSize, fused.size());

        List<String> documents = new ArrayList<>(head.size());
        for (RecallHit h : head) {
            documents.add(documentFor(h));
        }

        double[] scores;
        try {
            scores = crossEncoder.scoreDocuments(queryText, documents, requestTimeout);
        } catch (RuntimeException e) {
            // Voyage unreachable, a timeout, a non-2xx, an incomplete response, or a bad document:
            // degrade to the LLM reranker, never worse than the RRF baseline (mirrors VectorArm).
            warnOnce("Cross-encoder rerank failed (" + e.getMessage()
                    + "); degrading to the LLM reranker for this request.");
            return null;
        }
        if (scores == null || scores.length != head.size()) {
            warnOnce("Cross-encoder returned " + (scores == null ? "null" : scores.length)
                    + " scores for " + head.size() + " documents; degrading to the LLM reranker.");
            return null;
        }

        // Order the head by calibrated score desc, tie-break by original RRF position (stable).
        List<Ranked> ranked = new ArrayList<>(head.size());
        for (int i = 0; i < head.size(); i++) {
            ranked.add(new Ranked(head.get(i), clamp01(scores[i]), i));
        }
        ranked.sort(Comparator.<Ranked>comparingDouble(r -> r.score).reversed()
                .thenComparingInt(r -> r.originalIndex));

        // Re-assemble: re-ranked head (carrying its calibrated score) then the untouched RRF tail; the
        // tail keeps its original RRF score so a downstream relevance gate still sees its signal.
        List<RecallHit> result = new ArrayList<>(fused.size());
        int rank = 1;
        for (Ranked r : ranked) {
            result.add(r.hit.withRankAndScore(rank, r.score));
            rank++;
        }
        for (RecallHit t : tail) {
            result.add(t.withRankAndScore(rank, t.score()));
            rank++;
        }
        return Result.calibrated(result);
    }

    /**
     * Build the document text for a candidate: its title and a bounded snippet on one line. Stored page
     * content (already sanitized at ingest, #6); bounded defensively here. The cross-encoder is
     * non-generative, so this text is only scored, never interpreted as instructions. Falls back to the
     * hit id when a candidate has neither title nor snippet, so the document is always non-blank.
     */
    private static String documentFor(RecallHit h) {
        String title = oneLine(h.title());
        String snippet = oneLine(boundSnippet(h.snippet()));
        StringBuilder d = new StringBuilder(title.length() + snippet.length() + 3);
        if (!title.isEmpty()) {
            d.append(title);
        }
        if (!snippet.isEmpty()) {
            if (d.length() > 0) {
                d.append(" — ");
            }
            d.append(snippet);
        }
        return d.length() == 0 ? h.id() : d.toString();
    }

    private void warnOnce(String message) {
        if (warnedDegraded.compareAndSet(false, true)) {
            log.warn(message);
        } else {
            log.debug(message);
        }
    }

    private static String boundSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        return snippet.length() <= MAX_SNIPPET_CHARS
                ? snippet
                : snippet.substring(0, MAX_SNIPPET_CHARS) + "…";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** A candidate paired with its cross-encoder score and original RRF position, for the stable sort. */
    private record Ranked(RecallHit hit, double score, int originalIndex) {}
}
