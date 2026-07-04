package com.agentmemory.autoimprove;

import com.agentmemory.config.ProviderAuth;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the auto-improve loop (issue #30), bound from {@code agent-memory.auto-improve} (the
 * {@code [auto_improve]} umbrella). The {@code [auto_improve.eval]} eval-gate sub-block (#31) binds
 * separately to its own properties type and simply nests under the same umbrella.
 *
 * @param requireApproval    when true, proposals are held in {@code pending_writes} as {@code proposed}
 *                           for human review; when false (default — a product decision, #150) they apply
 *                           through the normal write path immediately.
 * @param maxAttempts        per-session review attempt cap, so a permanently-failing session does not
 *                           retry forever; default 3.
 * @param maxSessionsPerTick how many freshly-finished sessions one scheduler tick reviews at most, to
 *                           bound a tick's work; default 20.
 * @param scheduler          the out-of-band (per-finished-session) review scheduler settings.
 * @param curatorActions     the scope-level curator corrective-action loop settings (issue #101).
 * @param review             the LLM session-review engine settings (issue #150).
 */
@ConfigurationProperties("agent-memory.auto-improve")
public record AutoImproveProperties(
        boolean requireApproval,
        int maxAttempts,
        int maxSessionsPerTick,
        Scheduler scheduler,
        CuratorActions curatorActions,
        Review review) {

    public AutoImproveProperties {
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        maxSessionsPerTick = maxSessionsPerTick <= 0 ? 20 : maxSessionsPerTick;
        scheduler = scheduler == null ? new Scheduler(null, null) : scheduler;
        curatorActions = curatorActions == null ? new CuratorActions(false, null, 0) : curatorActions;
        review = review == null ? new Review(null, 0, 0, 0, null, 0, null) : review;
    }

    /**
     * The review scheduler ({@code [auto_improve.scheduler]}).
     *
     * @param enabled  master switch for out-of-band reviews; <strong>default true</strong> (#150 — the
     *                 loop runs unless the operator turns it off; a {@link Boolean} so an absent property
     *                 binds to the default rather than {@code false}).
     * @param interval delay between (non-overlapping) ticks; default 15m, must be {@code > 0}.
     */
    public record Scheduler(Boolean enabled, Duration interval) {
        public Scheduler {
            enabled = enabled == null ? Boolean.TRUE : enabled;
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

    /**
     * The LLM session-review engine ({@code [auto_improve.review]}, issue #150): extracts durable
     * knowledge (concepts/decisions/gotchas/procedures/rules) from each freshly-finished session,
     * adversarially verifies low-confidence proposals, dedupes against existing pages via recall, and
     * stages the survivors through the approval gate.
     *
     * @param enabled              master switch; <strong>default true</strong> (#150). A {@link Boolean}
     *                             so an absent property binds to the default rather than {@code false}.
     * @param maxLlmCallsPerTick   LLM-call budget per tick (extraction + verification calls); when spent,
     *                             remaining sessions wait for the next tick without consuming claims;
     *                             default 30.
     * @param minObservations      sessions with fewer observations are skipped as not worth an LLM call
     *                             (the claim IS consumed — a trivial session stays reviewed); default 5.
     * @param verifyThreshold      proposals with extraction confidence below this go through the phase-B
     *                             adversarial verification call; at/above it they skip straight to dedup;
     *                             default 0.8 (0..1).
     * @param quietHours           optional preferred window as {@code "HH:mm-HH:mm"} (<strong>UTC</strong>,
     *                             the repo's clock convention; may wrap midnight; {@code start == end}
     *                             means "always"); outside it the engine only runs when the backlog
     *                             exceeds {@link #quietHoursBacklog}; blank (default) = always run.
     * @param quietHoursBacklog    the total-due-session count (uncapped, not one tick's page) that
     *                             overrides {@link #quietHours}; default 50.
     * @param auth                 optional dedicated provider for review calls (the shape of
     *                             {@code llm.auth}), mirroring the recall seam (#146); unset = the primary
     *                             chat provider.
     */
    public record Review(
            Boolean enabled,
            int maxLlmCallsPerTick,
            int minObservations,
            double verifyThreshold,
            String quietHours,
            int quietHoursBacklog,
            ProviderAuth auth) {
        public Review {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            maxLlmCallsPerTick = maxLlmCallsPerTick <= 0 ? 30 : maxLlmCallsPerTick;
            minObservations = minObservations <= 0 ? 5 : minObservations;
            verifyThreshold = (verifyThreshold <= 0 || verifyThreshold > 1) ? 0.8 : verifyThreshold;
            quietHours = quietHours == null ? "" : quietHours.strip();
            quietHoursBacklog = quietHoursBacklog <= 0 ? 50 : quietHoursBacklog;
        }
    }
}
