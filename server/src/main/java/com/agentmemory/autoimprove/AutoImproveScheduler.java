package com.agentmemory.autoimprove;

import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The out-of-band auto-improve scheduler (issue #30): on each tick it establishes per-project watermarks,
 * finds freshly-finished sessions due for review, and runs each through every wired
 * {@link ProposalSource review engine} into the approval {@link AutoImproveGate}. On by default (#150);
 * a single non-overlapping timer thread.
 *
 * <h2>Why out-of-band</h2>
 * Review is never on a hook/ingest/admission path — it runs on its own cadence so capturing a session and
 * serving recall stay fast, and a slow or failing review can never block or corrupt capture. The work is
 * idempotent and resumable: the watermark prevents retro-review, and the per-(session, source) claim
 * de-dupes overlapping ticks and caps retries.
 *
 * <h2>Multiple engines (#150)</h2>
 * Sources compose: the #29 rule-based curator adapter and the #150 LLM session-review engine each keep
 * their own claim namespace ({@link ProposalSource#id()}), so one engine finishing a session leaves it
 * due for the other. Per source and per tick the scheduler calls {@link ProposalSource#beginTick()},
 * consults {@link ProposalSource#ready(int)} <em>before each claim</em> (a not-ready source — rate-limit
 * gate paused, budget spent, quiet hours — stops its pass without consuming claims; deferral is not
 * failure), and flushes {@link ProposalSource#endTick()} aggregates through the gate. With no source
 * wired, a tick logs and returns <em>before</em> claiming anything.
 */
public class AutoImproveScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AutoImproveScheduler.class);

    private final JdbcAutoImproveStateRepository state;
    private final AutoImproveGate gate;
    private final java.util.function.Supplier<List<ProposalSource>> sources;
    private final AutoImproveProperties props;

    /** Guards against a manual tick overlapping a timer tick (the timer itself never self-overlaps). */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    private volatile ScheduledExecutorService executor;

    public AutoImproveScheduler(
            JdbcAutoImproveStateRepository state,
            AutoImproveGate gate,
            java.util.function.Supplier<List<ProposalSource>> sources,
            AutoImproveProperties props) {
        this.state = state;
        this.gate = gate;
        this.sources = sources;
        this.props = props;
    }

    /**
     * Run one review pass. Idempotent and safe to call directly (tests, a future "run now"): a second
     * concurrent call returns immediately rather than overlapping. Establishes watermarks, then reviews
     * each due session under its own claim.
     */
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("auto-improve: a tick is already running; skipping this one");
            return;
        }
        try {
            List<ProposalSource> engines = sources.get();
            if (engines == null || engines.isEmpty()) {
                // No engine: do NOT establish watermarks or claim — that would mark sessions reviewed
                // against an empty engine and they'd never be revisited.
                log.warn("auto-improve: scheduler ran but no proposal source is wired; nothing reviewed");
                return;
            }
            state.establishWatermarks();
            for (ProposalSource engine : engines) {
                runSourcePass(engine);
            }
        } catch (RuntimeException e) {
            log.warn("auto-improve: tick failed: {}", e.toString());
        } finally {
            ticking.set(false);
        }
    }

    /** One source's pass over its due sessions, plus its end-of-tick flush. Failures stay per-source. */
    private void runSourcePass(ProposalSource engine) {
        try {
            engine.beginTick();
            // ready() gets the UNCAPPED backlog: a backlog-sensitive policy (the review engine's
            // quiet-hours override, #150) must see the real queue depth, not one tick's LIMIT-ed page.
            int backlog = state.countDue(engine.id(), props.maxAttempts());
            List<DueSession> due = state.dueSessions(
                    engine.id(), props.maxAttempts(), props.maxSessionsPerTick());
            log.debug("auto-improve[{}]: {} session(s) due for review ({} this tick)",
                    engine.id(), backlog, due.size());
            int reviewed = 0;
            try {
                for (DueSession d : due) {
                    if (!engine.ready(backlog)) {
                        // Deferral, not failure: the remaining sessions keep no claim and stay due, so a
                        // paused rate-limit gate / spent budget / quiet hours cost nothing (#148/#150).
                        log.debug("auto-improve[{}]: source not ready after {} review(s); deferring {} "
                                + "session(s) to a later tick", engine.id(), reviewed, due.size() - reviewed);
                        break;
                    }
                    reviewOne(engine, d.scope(), d.session());
                    reviewed++;
                }
            } finally {
                // Flush the tick aggregates even when the pass aborts mid-way — the notes describe the
                // reviews that DID happen, and losing them silently would hide rejections/conversions.
                for (ProposalSource.ScopedWrite report : engine.endTick()) {
                    try {
                        gate.submit(report.scope(), null, report.write());
                    } catch (RuntimeException e) {
                        log.warn("auto-improve[{}]: tick report for {}/{} failed: {}", engine.id(),
                                report.scope().workspaceSlug(), report.scope().projectSlug(), e.toString());
                    }
                }
            }
        } catch (RuntimeException e) {
            // One engine failing must not starve the others (e.g. the LLM review dying must not stop
            // the rule-based curator).
            log.warn("auto-improve[{}]: source pass failed: {}", engine.id(), e.toString());
        }
    }

    /** Claim, review, and record the outcome of one session. A failure is recorded for bounded retry. */
    private void reviewOne(ProposalSource engine, Scope scope, SessionId session) {
        if (!state.claim(scope, session, props.maxAttempts(), engine.id())) {
            return; // a parallel tick took it, or it's done / over the attempt cap
        }
        try {
            List<ProposedWrite> proposals = engine.proposalsFor(scope, session);
            for (ProposedWrite write : proposals) {
                gate.submit(scope, session, write);
            }
            state.markDone(session, engine.id());
            log.debug("auto-improve[{}]: reviewed session {} -> {} proposal(s)",
                    engine.id(), session.value(), proposals.size());
        } catch (RuntimeException e) {
            state.markFailed(session, engine.id(), e.toString());
            log.warn("auto-improve[{}]: review of session {} failed (will retry while under cap): {}",
                    engine.id(), session.value(), e.toString());
        }
    }

    // --- SmartLifecycle: the off-by-default timer ---------------------------------------------------

    @Override
    public synchronized void start() {
        if (!props.scheduler().enabled()) {
            log.debug("auto-improve scheduler disabled (agent-memory.auto-improve.scheduler.enabled=false)");
            return;
        }
        if (executor != null) {
            return;
        }
        long delayMillis = props.scheduler().interval().toMillis();
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-improve-scheduler");
            t.setDaemon(true);
            return t;
        });
        // Fixed delay (not fixed rate): the next tick starts only after the previous finishes, so a long
        // tick never overlaps the next.
        exec.scheduleWithFixedDelay(this::tick, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        executor = exec;
        log.info("auto-improve scheduler started (interval={})", props.scheduler().interval());
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService exec = executor;
        executor = null;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
