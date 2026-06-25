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
 * @param scheduler          the out-of-band review scheduler settings.
 */
@ConfigurationProperties("agent-memory.auto-improve")
public record AutoImproveProperties(
        boolean requireApproval,
        int maxAttempts,
        int maxSessionsPerTick,
        Scheduler scheduler) {

    public AutoImproveProperties {
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        maxSessionsPerTick = maxSessionsPerTick <= 0 ? 20 : maxSessionsPerTick;
        scheduler = scheduler == null ? new Scheduler(false, null) : scheduler;
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
}
