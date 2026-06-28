package com.agentmemory.llmrecall;

import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The LLM-assisted {@link RecallService} (issue #21): a decorator over the base hybrid recall
 * ({@link com.agentmemory.recall.HybridRecallService}) that adds query expansion, an LLM re-rank of
 * the fused candidates, and access reinforcement on the returned hits — without reshaping the
 * {@link RecallService} interface (its Javadoc anticipates exactly this: "#21 (LLM query-expansion /
 * re-rank) layer onto without reshaping the interface").
 *
 * <h2>Pipeline (per query)</h2>
 * <ol>
 *   <li><strong>Budget</strong>: a per-query LLM-call budget ({@link LlmRecallProperties#maxCallsPerQuery()})
 *       gates every model step; when exhausted (or the feature is disabled) the step is skipped and the
 *       RRF result is used as-is — the non-LLM fast path.</li>
 *   <li><strong>Expand</strong> (optional): rewrite the query into extra retrieval terms before
 *       retrieval ({@link QueryExpander}). Best-effort; failure falls back to the original text.</li>
 *   <li><strong>Retrieve</strong>: run the base hybrid search, over-fetching a candidate pool (≥
 *       {@code maxCandidates}) so the re-ranker has more than the final page of hits to choose
 *       from.</li>
 *   <li><strong>Re-rank</strong> (optional): re-order the fused candidates by LLM relevance
 *       ({@link CandidateReranker}), capped to the top {@code maxCandidates}. Skipped for the
 *       raw-observation fallback and for trivially small result sets. Failure keeps RRF order.</li>
 *   <li><strong>Trim</strong>: cut to the caller's requested {@code limit}.</li>
 *   <li><strong>Reinforce</strong>: fire {@link AccessReinforcer} on the final hits (#24 seam) — a
 *       best-effort side effect that never fails the query. The reinforcing UPDATE runs <em>off the
 *       response hot path</em> on a bounded single-thread executor (issue #130 follow-up), so a slow DB
 *       cannot add latency to {@code /recall/inject}.</li>
 * </ol>
 *
 * <p><strong>Per-stage observability (issue #130 follow-up).</strong> Each search records its
 * retrieve / re-rank / expand stage latencies through {@link RecallMetrics} — a structured timing log
 * plus, when a Micrometer registry is present, per-stage timers — so the {@code /recall/inject}
 * "p50/p95 ≤ 3s, per-stage" criterion is measurable. Telemetry is side-effect-only and never alters a
 * result.
 *
 * <p><strong>Wall-clock budget (issue #130).</strong> Beyond the per-query call budget, the LLM steps
 * share a hard time budget ({@link LlmRecallProperties#budgetMs()}, set under the client's 10s deadline).
 * On exhaustion at any step the step is abandoned and the already-computed hybrid (RRF) hits are
 * returned — so {@code /recall/inject} degrades to the fast hybrid result instead of the client timing
 * out with nothing. The remaining budget is also handed to each call as a real per-call HTTP timeout, so
 * a single slow call is cancelled inside the budget rather than running for the provider's full timeout.
 *
 * <p><strong>Never worse than the baseline.</strong> Every LLM step degrades to the RRF result on
 * failure, timeout, or over-budget, so this service returns the same hits as the base search would when
 * the LLM is unavailable, and a re-ordered (better) page when it is. Stateless given its collaborators.
 */
public final class LlmRecallService implements RecallService, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LlmRecallService.class);

    /** Bounded reinforcement backlog; keeps the off-hot-path dispatch non-blocking yet bounded. */
    private static final int REINFORCE_QUEUE_CAPACITY = 256;

    private final RecallService base;
    private final QueryExpander expander; // nullable when expansion is disabled
    private final CandidateReranker reranker;
    private final AccessReinforcer reinforcer;
    private final LlmRecallProperties props;
    private final LongSupplier nowMs;
    private final RecallMetrics metrics;
    /** Single-thread, bounded executor that runs access reinforcement off the response hot path. */
    private final ThreadPoolExecutor reinforceExecutor;

    public LlmRecallService(
            RecallService base,
            QueryExpander expander,
            CandidateReranker reranker,
            AccessReinforcer reinforcer,
            LlmRecallProperties props) {
        this(base, expander, reranker, reinforcer, props, System::currentTimeMillis, new RecallMetrics(null));
    }

    /** Fully-injectable constructor (clock) for hermetic budget-degradation tests; no-op metrics. */
    LlmRecallService(
            RecallService base,
            QueryExpander expander,
            CandidateReranker reranker,
            AccessReinforcer reinforcer,
            LlmRecallProperties props,
            LongSupplier nowMs) {
        this(base, expander, reranker, reinforcer, props, nowMs, new RecallMetrics(null));
    }

    /** Fully-injectable constructor (clock + telemetry); the wiring path supplies the real metrics. */
    public LlmRecallService(
            RecallService base,
            QueryExpander expander,
            CandidateReranker reranker,
            AccessReinforcer reinforcer,
            LlmRecallProperties props,
            LongSupplier nowMs,
            RecallMetrics metrics) {
        if (base == null) {
            throw new IllegalArgumentException("base RecallService must not be null");
        }
        if (reranker == null) {
            throw new IllegalArgumentException("reranker must not be null");
        }
        if (reinforcer == null) {
            throw new IllegalArgumentException("reinforcer must not be null");
        }
        if (props == null) {
            throw new IllegalArgumentException("props must not be null");
        }
        if (nowMs == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.base = base;
        this.expander = expander;
        this.reranker = reranker;
        this.reinforcer = reinforcer;
        this.props = props;
        this.nowMs = nowMs;
        this.metrics = metrics;
        // One thread keeps reinforcement serialized; a bounded backlog drops on overflow (best-effort —
        // reinforcement is an additive bump that re-fires on the next recall) rather than blocking.
        this.reinforceExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(REINFORCE_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "agent-memory-recall-reinforce");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public RecallResult search(RecallQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("recall query must not be null");
        }
        RecallResult result = doSearch(query);
        // Reinforce the pages actually returned (issue #24 seam) OFF the response hot path (issue #130
        // follow-up): the bump is a synchronous JDBC UPDATE that runs after doSearch and outside the LLM
        // budget, so a slow DB would otherwise add straight to /recall/inject latency. Best-effort; never
        // throws into the request.
        reinforceOffHotPath(query.scope(), result.hits());
        return result;
    }

    private RecallResult doSearch(RecallQuery query) {
        long startNanos = System.nanoTime();

        // Pure fast path: feature off or no call budget — delegate straight to the base, untouched. Still
        // timed (retrieve == total) so the fast path is as observable as the LLM-assisted one.
        if (!props.enabled() || props.maxCallsPerQuery() <= 0) {
            RecallResult out = base.search(query);
            long retrieveNanos = System.nanoTime() - startNanos;
            metrics.recordSearch(RecallMetrics.STAGE_SKIPPED, retrieveNanos, RecallMetrics.STAGE_SKIPPED,
                    retrieveNanos, out.hits().size(), out.hits().size());
            return out;
        }

        // Wall-clock budget for the LLM steps (issue #130). Tier 0 (the hybrid retrieve below) always
        // runs and is the degradation target; on budget exhaustion at any LLM step we ABANDON that step
        // and return the already-computed hybrid hits — never empty, never worse than the RRF baseline.
        long deadlineMs = nowMs.getAsLong() + props.budgetMs();

        int callBudget = props.maxCallsPerQuery();

        // Budget is spent on RE-RANK FIRST (the higher-value step), per the documented contract: reserve
        // a call for it before letting expansion draw from the budget, so a tight budget (e.g. a single
        // call) funds the re-rank rather than being consumed by expansion. Re-rank runs after retrieval,
        // so we cannot know the candidate count yet — but reserving the call here is enough to enforce
        // the priority; whether re-rank actually fires is re-checked against the real candidate count.
        boolean rerankIntended = callBudget > 0;
        int budgetAfterRerankReserve = rerankIntended ? callBudget - 1 : callBudget;

        // Per-stage timings (issue #130 follow-up). STAGE_SKIPPED marks a stage that did not run.
        long expandNanos = RecallMetrics.STAGE_SKIPPED;
        long rerankNanos = RecallMetrics.STAGE_SKIPPED;

        // 1. Optional query expansion, funded only by whatever budget remains after the re-rank reserve
        // AND only while time remains. The call is bounded by a real per-call timeout = remaining budget.
        String text = query.text();
        if (expander != null && props.expansion().enabled() && budgetAfterRerankReserve > 0) {
            Duration remaining = remaining(deadlineMs);
            if (remaining != null) {
                long t = System.nanoTime();
                text = expander.expand(text, remaining); // best-effort: original text on any failure
                expandNanos = System.nanoTime() - t;
            }
        }

        // 2. Retrieve. Over-fetch a larger candidate pool ONLY when a re-rank is intended (it needs
        // candidates beyond the final page); otherwise fetch exactly the caller's limit so a non-rerank
        // query does no wasted DB work.
        int retrievalLimit = rerankIntended
                ? Math.max(query.limit(), props.maxCandidates())
                : query.limit();
        long retrieveStart = System.nanoTime();
        RecallResult base0 = base.search(new RecallQuery(text, query.scope(), retrievalLimit));
        long retrieveNanos = System.nanoTime() - retrieveStart;

        // The raw-observation fallback is a low-confidence, bounded path; do not spend an LLM re-rank
        // on it — just trim it to the requested limit and return (still flagged as a fallback).
        if (base0.rawFallback()) {
            RecallResult out = trim(base0, query.limit());
            metrics.recordSearch(expandNanos, retrieveNanos, rerankNanos, System.nanoTime() - startNanos,
                    base0.hits().size(), out.hits().size());
            return out;
        }

        List<RecallHit> hits = base0.hits();
        int candidates = hits.size();

        // 3. LLM re-rank, using the reserved call. Skip when too few candidates make it pointless (RRF
        // order already suffices). Also skip when the budget is already spent: abandon the LLM step and
        // return the hybrid hits, bounding /recall/inject under the client's deadline. When it does run,
        // the remaining budget is passed as a real per-call HTTP timeout (cancellation, not a post-hoc
        // check) — a timeout there degrades to RRF order, same as any other rerank failure.
        if (rerankIntended && hits.size() >= props.minRerankCandidates()) {
            Duration remaining = remaining(deadlineMs);
            if (remaining != null) {
                long t = System.nanoTime();
                hits = reranker.rerank(text, hits, remaining);
                rerankNanos = System.nanoTime() - t;
            } else {
                log.debug("recall budget exhausted before re-rank; returning hybrid result");
            }
        }

        // 4. Trim to the caller's limit (re-stamping ranks so the returned page is 1..limit).
        RecallResult out = trimPages(hits, query.limit());
        metrics.recordSearch(expandNanos, retrieveNanos, rerankNanos, System.nanoTime() - startNanos,
                candidates, out.hits().size());
        return out;
    }

    /**
     * Dispatch access reinforcement of the returned hits to the bounded off-hot-path executor. The
     * recall response never waits on (or fails because of) the reinforcing UPDATE; a saturated backlog or
     * a shutting-down executor simply drops the bump (it re-fires on the next recall).
     */
    private void reinforceOffHotPath(Scope scope, List<RecallHit> hits) {
        try {
            reinforceExecutor.execute(() -> {
                try {
                    reinforcer.reinforce(scope, hits);
                } catch (RuntimeException e) {
                    // A read's side effect must never escalate; the reinforcer is best-effort anyway.
                    log.debug("access reinforcement failed off the hot path for {}/{}: {}",
                            scope.workspaceSlug(), scope.projectSlug(), e.toString());
                }
            });
        } catch (RejectedExecutionException reject) {
            if (reinforceExecutor.isShutdown()) {
                log.debug("recall reinforcement executor shutting down; skipped reinforcement for {}/{}",
                        scope.workspaceSlug(), scope.projectSlug());
            } else {
                log.debug("recall reinforcement backlog saturated; dropped reinforcement for {}/{} "
                        + "({} hits, re-fires on the next recall)",
                        scope.workspaceSlug(), scope.projectSlug(), hits.size());
            }
        }
    }

    /**
     * The time left before {@code deadlineMs} as a positive {@link Duration}, or {@code null} when the
     * budget is exhausted (the caller then abandons that LLM step and keeps the hybrid result).
     */
    private Duration remaining(long deadlineMs) {
        long left = deadlineMs - nowMs.getAsLong();
        return left > 0 ? Duration.ofMillis(left) : null;
    }

    private static RecallResult trim(RecallResult result, int limit) {
        if (result.hits().size() <= limit) {
            return result;
        }
        List<RecallHit> capped = result.hits().subList(0, limit);
        return result.rawFallback()
                ? RecallResult.ofRawFallback(capped)
                : RecallResult.ofPages(capped);
    }

    private static RecallResult trimPages(List<RecallHit> hits, int limit) {
        int n = Math.min(limit, hits.size());
        List<RecallHit> capped = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // Re-stamp the final 1-based rank; keep each hit's (re-ranked) score.
            RecallHit h = hits.get(i);
            capped.add(h.withRankAndScore(i + 1, h.score()));
        }
        return RecallResult.ofPages(capped);
    }

    /** @return the wrapped base service (used by the injection layer's fast path). */
    public RecallService base() {
        return base;
    }

    /** Shut the reinforcement executor down with the context, letting an in-flight bump finish briefly. */
    @Override
    public void close() {
        reinforceExecutor.shutdown();
        try {
            if (!reinforceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                reinforceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reinforceExecutor.shutdownNow();
        }
    }
}
