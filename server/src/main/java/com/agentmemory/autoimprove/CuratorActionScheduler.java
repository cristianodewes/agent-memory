package com.agentmemory.autoimprove;

import com.agentmemory.recall.Scope;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The out-of-band <strong>scope-level</strong> curator-action loop (issue #101): on each tick it
 * enumerates the projects that have pages and audits each one — turning the #29 curator's findings into
 * corrective-action proposals ({@code page.forget}, {@code link.fix}) through the approval
 * {@link AutoImproveGate}. <strong>Off by default</strong> (config), a single non-overlapping timer
 * thread when enabled.
 *
 * <h2>Distinct cadence from {@link AutoImproveScheduler}</h2>
 * The #30 scheduler reviews each freshly-<em>finished session</em> (its watermark + per-session claim
 * de-dupe history). The curator is scope-level — it audits the whole project — so this loop's unit is a
 * <em>project</em>, on its own (coarser) cadence. It needs no session watermark; instead it stays
 * quiescent by skipping a finding whose corrective action is already pending or was rejected
 * ({@link JdbcPendingWriteRepository#openActionKeys}). Applied forget/link-fix actions remove the finding
 * itself, so they cannot recur.
 *
 * <h2>Out-of-band</h2>
 * Like the #30 loop, review/repair is never on a hook/ingest/recall path; a slow or failing audit can
 * never block capture. Each action is eval-gated (#31) and audited through the same gate, fail-closed.
 */
public class CuratorActionScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CuratorActionScheduler.class);

    private final CuratorActionRepository scopes;
    private final JdbcPendingWriteRepository pending;
    private final AutoImproveGate gate;
    private final CuratorActionProposalSource source;
    private final AutoImproveProperties props;

    /** Guards against a manual tick overlapping a timer tick (the timer itself never self-overlaps). */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    private volatile ScheduledExecutorService executor;

    public CuratorActionScheduler(
            CuratorActionRepository scopes,
            JdbcPendingWriteRepository pending,
            AutoImproveGate gate,
            CuratorActionProposalSource source,
            AutoImproveProperties props) {
        this.scopes = scopes;
        this.pending = pending;
        this.gate = gate;
        this.source = source;
        this.props = props;
    }

    /**
     * Run one audit pass over every project with pages. Idempotent and safe to call directly (tests, a
     * future "run now"): a second concurrent call returns immediately rather than overlapping.
     */
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("curator-actions: a tick is already running; skipping this one");
            return;
        }
        try {
            List<Scope> due = scopes.scopesWithPages(props.curatorActions().maxScopesPerTick());
            log.debug("curator-actions: auditing {} scope(s)", due.size());
            for (Scope scope : due) {
                runScope(scope);
            }
        } catch (RuntimeException e) {
            log.warn("curator-actions: tick failed: {}", e.toString());
        } finally {
            ticking.set(false);
        }
    }

    /**
     * Audit one project: map its actionable findings to corrective-action proposals and submit each
     * (skipping any whose action is already pending/rejected) through the gate. Directly callable for a
     * single-scope run.
     *
     * @param scope the project to audit; never null.
     * @return the number of new proposals submitted to the gate.
     */
    public int runScope(Scope scope) {
        List<ProposedWrite> actions = source.actionsFor(scope);
        if (actions.isEmpty()) {
            return 0;
        }
        Set<String> open = pending.openActionKeys(scope);
        int submitted = 0;
        for (ProposedWrite action : actions) {
            String key = action.kind() + '|' + action.path();
            if (open.contains(key)) {
                log.debug("curator-actions: {} already pending — skipping", key);
                continue;
            }
            try {
                gate.submit(scope, null, action); // scope-level: no originating session
                submitted++;
            } catch (RuntimeException e) {
                // One failing action must not abort the scope; the gate left the row recoverable.
                log.warn("curator-actions: submit of {} for {} failed: {}",
                        action.kind(), action.path(), e.toString());
            }
        }
        return submitted;
    }

    // --- SmartLifecycle: the off-by-default timer ---------------------------------------------------

    @Override
    public synchronized void start() {
        if (!props.curatorActions().enabled()) {
            log.debug("curator-action loop disabled "
                    + "(agent-memory.auto-improve.curator-actions.enabled=false)");
            return;
        }
        if (executor != null) {
            return;
        }
        long delayMillis = props.curatorActions().interval().toMillis();
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "curator-action-scheduler");
            t.setDaemon(true);
            return t;
        });
        // Fixed delay (not fixed rate): the next tick starts only after the previous finishes.
        exec.scheduleWithFixedDelay(this::tick, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        executor = exec;
        log.info("curator-action loop started (interval={})", props.curatorActions().interval());
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
