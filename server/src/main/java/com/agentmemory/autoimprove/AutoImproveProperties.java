package com.agentmemory.autoimprove;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the auto-improve loop (issue #30), bound from {@code agent-memory.auto-improve} (the
 * {@code [auto_improve]} umbrella). The {@code [auto_improve.eval]} eval-gate sub-block (#31) binds
 * separately to its own properties type and simply nests under the same umbrella.
 *
 * @param requireApproval    when true, proposals are held in {@code pending_writes} as {@code proposed}
 *                           for human review; when false (default) they apply through the normal write
 *                           path immediately.
 * @param maxAttempts        per-session review attempt cap, so a permanently-failing session does not
 *                           retry forever; default 3.
 * @param maxSessionsPerTick how many freshly-finished sessions one scheduler tick reviews at most, to
 *                           bound a tick's work; default 20.
 * @param scheduler          the out-of-band (per-finished-session) review scheduler settings.
 * @param curatorActions     the scope-level curator corrective-action loop settings (issue #101).
 */
@ConfigurationProperties("agent-memory.auto-improve")
public record AutoImproveProperties(
        boolean requireApproval,
        int maxAttempts,
        int maxSessionsPerTick,
        Scheduler scheduler,
        CuratorActions curatorActions) {

    public AutoImproveProperties {
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        maxSessionsPerTick = maxSessionsPerTick <= 0 ? 20 : maxSessionsPerTick;
        scheduler = scheduler == null ? new Scheduler(false, null) : scheduler;
        curatorActions = curatorActions == null ? new CuratorActions(false, null, 0) : curatorActions;
    }

    /**
     * The review scheduler ({@code [auto_improve.scheduler]}).
     *
     * @param enabled  master switch for out-of-band reviews; default false (off).
     * @param interval delay between (non-overlapping) ticks; default 15m, must be {@code > 0}.
     */
    public record Scheduler(boolean enabled, Duration interval) {
        public Scheduler {
            interval = (interval == null || interval.isZero() || interval.isNegative())
                    ? Duration.ofMinutes(15)
                    : interval;
        }
    }

    /**
     * The scope-level curator corrective-action loop ({@code [auto_improve.curator-actions]}, issue
     * #101). Distinct from {@link Scheduler}: that one reviews each freshly-<em>finished session</em>;
     * this one audits a whole <em>project (scope)</em> on its own cadence and turns the #29 curator's
     * findings into corrective actions (forget a cold page, prune a dangling cross-project link).
     *
     * @param enabled          master switch; default false (off) — corrective actions never fire unless
     *                         {@code agent-memory.auto-improve.curator-actions.enabled=true}.
     * @param interval         delay between (non-overlapping) scope-review ticks; default 60m, must be
     *                         {@code > 0} — coarser than the per-session loop because the curator audits
     *                         the whole project.
     * @param maxScopesPerTick how many projects one tick audits at most, to bound a tick's work; default 50.
     */
    public record CuratorActions(boolean enabled, Duration interval, int maxScopesPerTick) {
        public CuratorActions {
            interval = (interval == null || interval.isZero() || interval.isNegative())
                    ? Duration.ofMinutes(60)
                    : interval;
            maxScopesPerTick = maxScopesPerTick <= 0 ? 50 : maxScopesPerTick;
        }
    }
}
