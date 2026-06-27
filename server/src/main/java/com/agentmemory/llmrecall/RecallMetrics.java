package com.agentmemory.llmrecall;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-stage latency telemetry for LLM-assisted recall (issue #130 follow-up: prove "p50/p95 ≤ 3s,
 * per-stage").
 *
 * <p>Emits a structured per-request timing line and, when a Micrometer {@link MeterRegistry} is on hand
 * (Spring Actuator is on the classpath, so one is auto-configured), also records per-stage {@link Timer}s
 * — overall {@code /recall/inject}, the base hybrid retrieve, the LLM re-rank, and query expansion when it
 * runs — so the recall path's p50/p95 is observable without standing up a new framework. The registry is
 * optional: with none present this degrades to the structured log alone (the project's existing
 * observability convention is Slf4j).
 *
 * <p><strong>Side-effect only.</strong> Telemetry must never change a recall result or fail a request;
 * every recording is best-effort and swallows its own errors. The timings are wall-clock {@code nanoTime}
 * deltas captured by the caller — cheap, monotonic, and carrying no PII (only durations and counts).
 */
public final class RecallMetrics {

    private static final Logger log = LoggerFactory.getLogger(RecallMetrics.class);

    /** End-to-end LLM-assisted search timer (retrieve plus the optional expand/re-rank steps). */
    static final String SEARCH_TIMER = "agentmemory.recall.search";
    /** One recall stage; tagged {@code stage=retrieve|rerank|expand}. */
    static final String STAGE_TIMER = "agentmemory.recall.stage";
    /** The curated {@code /recall/inject} endpoint timer (search plus gate and render). */
    static final String INJECT_TIMER = "agentmemory.recall.inject";

    /** Sentinel duration for a stage that did not run this query (re-rank/expand are optional). */
    static final long STAGE_SKIPPED = -1L;

    private final MeterRegistry registry; // nullable: structured-log-only when no registry is present

    public RecallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Record one LLM-assisted search: a structured timing line plus, when a registry is present, the
     * total and per-stage timers. A stage given {@link #STAGE_SKIPPED} did not run and is omitted from
     * the metrics; {@code retrieve} always runs.
     *
     * @param expandNanos   query-expansion duration, or {@link #STAGE_SKIPPED} when expansion did not run.
     * @param retrieveNanos base hybrid retrieve duration (always measured).
     * @param rerankNanos   LLM re-rank duration, or {@link #STAGE_SKIPPED} when the re-rank did not run.
     * @param totalNanos    total wall-clock of the search.
     * @param candidates    the fused candidate pool size the re-rank chose from.
     * @param hits          the number of hits actually returned.
     */
    public void recordSearch(
            long expandNanos, long retrieveNanos, long rerankNanos, long totalNanos,
            int candidates, int hits) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "recall stages: total={}ms retrieve={}ms rerank={}ms expand={}ms candidates={} hits={}",
                    ms(totalNanos), ms(retrieveNanos), msOrSkip(rerankNanos), msOrSkip(expandNanos),
                    candidates, hits);
        }
        if (registry == null) {
            return;
        }
        try {
            record(SEARCH_TIMER, totalNanos);
            recordStage("retrieve", retrieveNanos);
            recordStage("rerank", rerankNanos);
            recordStage("expand", expandNanos);
        } catch (RuntimeException e) {
            // Telemetry must never fail recall; drop the sample and move on.
            log.debug("recall search metrics skipped: {}", e.toString());
        }
    }

    /**
     * Record the end-to-end {@code /recall/inject} latency: a structured line plus the endpoint timer.
     *
     * @param totalNanos total wall-clock of the injection (search plus gate and render).
     * @param hits       the number of hits rendered into the injected block.
     */
    public void recordInject(long totalNanos, int hits) {
        if (log.isDebugEnabled()) {
            log.debug("recall/inject: total={}ms hits={}", ms(totalNanos), hits);
        }
        if (registry == null) {
            return;
        }
        try {
            record(INJECT_TIMER, totalNanos);
        } catch (RuntimeException e) {
            log.debug("recall inject metrics skipped: {}", e.toString());
        }
    }

    private void record(String name, long nanos) {
        registry.timer(name).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    private void recordStage(String stage, long nanos) {
        if (nanos == STAGE_SKIPPED) {
            return; // the stage did not run this query
        }
        registry.timer(STAGE_TIMER, "stage", stage).record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    private static long ms(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos));
    }

    /** A skipped stage logs as {@code skip} so it reads differently from a genuine 0ms stage. */
    private static Object msOrSkip(long nanos) {
        return nanos == STAGE_SKIPPED ? "skip" : ms(nanos);
    }
}
