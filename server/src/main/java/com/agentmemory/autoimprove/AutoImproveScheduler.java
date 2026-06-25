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
 * finds freshly-finished sessions due for review, and runs each through the {@link ProposalSource review
 * engine} into the approval {@link AutoImproveGate}. Off by default; a single non-overlapping timer thread
 * when enabled.
 *
 * <h2>Why out-of-band</h2>
 * Review is never on a hook/ingest/admission path — it runs on its own cadence so capturing a session and
 * serving recall stay fast, and a slow or failing review can never block or corrupt capture. The work is
 * idempotent and resumable: the watermark prevents retro-review, and the per-session claim de-dupes
 * overlapping ticks and caps retries.
 *
 * <h2>Deferred engine</h2>
 * The {@link ProposalSource} (the #29 curator / #19 consolidation that turns a session into proposals) is
 * injected via a provider and is <strong>not wired in this PR</strong>. With no source, a tick logs and
 * returns <em>before</em> claiming anything — so it never burns sessions as "reviewed" with an empty
 * engine; once the source lands, those sessions are reviewed normally.
 */
public class AutoImproveScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AutoImproveScheduler.class);

    private final JdbcAutoImproveStateRepository state;
    private final AutoImproveGate gate;
    private final java.util.function.Supplier<ProposalSource> source;
    private final AutoImproveProperties props;

    /** Guards against a manual tick overlapping a timer tick (the timer itself never self-overlaps). */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    private volatile ScheduledExecutorService executor;

    public AutoImproveScheduler(
            JdbcAutoImproveStateRepository state,
            AutoImproveGate gate,
            java.util.function.Supplier<ProposalSource> source,
            AutoImproveProperties props) {
        this.state = state;
        this.gate = gate;
        this.source = source;
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
            ProposalSource engine = source.get();
            if (engine == null) {
                // Deferred engine (#29/#19): do NOT establish watermarks or claim — that would mark
                // sessions reviewed against an empty engine and they'd never be revisited.
                log.warn("auto-improve: scheduler ran but no proposal source is wired "
                        + "(deferred to #29/#19); nothing reviewed");
                return;
            }
            state.establishWatermarks();
            List<DueSession> due = state.dueSessions(props.maxAttempts(), props.maxSessionsPerTick());
            log.debug("auto-improve: {} session(s) due for review", due.size());
            for (DueSession d : due) {
                reviewOne(engine, d.scope(), d.session());
            }
        } catch (RuntimeException e) {
            log.warn("auto-improve: tick failed: {}", e.toString());
        } finally {
            ticking.set(false);
        }
    }

    /** Claim, review, and record the outcome of one session. A failure is recorded for bounded retry. */
    private void reviewOne(ProposalSource engine, Scope scope, SessionId session) {
        if (!state.claim(scope, session, props.maxAttempts())) {
            return; // a parallel tick took it, or it's done / over the attempt cap
        }
        try {
            List<ProposedWrite> proposals = engine.proposalsFor(scope, session);
            for (ProposedWrite write : proposals) {
                gate.submit(scope, session, write);
            }
            state.markDone(session);
            log.debug("auto-improve: reviewed session {} -> {} proposal(s)",
                    session.value(), proposals.size());
        } catch (RuntimeException e) {
            state.markFailed(session, e.toString());
            log.warn("auto-improve: review of session {} failed (will retry while under cap): {}",
                    session.value(), e.toString());
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
