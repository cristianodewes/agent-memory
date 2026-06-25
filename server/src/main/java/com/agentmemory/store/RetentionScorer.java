package com.agentmemory.store;

import com.agentmemory.core.MemoryLayer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The retention/decay math (issue #24; ARCHITECTURE §3.3) — the <strong>single</strong>
 * implementation shared by recall ranking (#15) and the forget sweep (#25), kept in
 * {@code com.agentmemory.store} on purpose so neither caller re-derives the curve.
 *
 * <h2>Formula</h2>
 * <pre>  score = salience·exp(−λ·Δt_days) + σ·log(1+access_count)·exp(−μ·days_since_access)</pre>
 * The first term is <em>passive decay</em>: a page's intrinsic salience fades with its age. The
 * second is <em>active reinforcement</em>: each recall hit (which bumps {@code access_count} and
 * {@code last_accessed_at}) lifts the score, and that lift itself relaxes the longer it has been
 * since the page was last touched. {@code log(1+access_count)} gives diminishing returns so a
 * hammered page cannot dominate purely on raw hit count.
 *
 * <h2>Per-layer regimes</h2>
 * The {@link MemoryLayer} selects <em>which</em> terms apply:
 * <ul>
 *   <li>{@link MemoryLayer#WORKING}/{@link MemoryLayer#EPISODIC} — both terms (age-decaying):
 *       freshness matters and use reinforces.</li>
 *   <li>{@link MemoryLayer#SEMANTIC} — the age term is dropped ({@code ageDecays()==false}); the
 *       page keeps its full salience and is only ever lifted by use. Timeless knowledge does not
 *       fade on the calendar.</li>
 *   <li>{@link MemoryLayer#PROCEDURAL} — also age-stable, but its retention is dominated by the
 *       reinforcement term: a runbook that is used stays hot, one that is not goes cold by the
 *       access term decaying, not by age.</li>
 * </ul>
 *
 * <p>Stateless and deterministic given a {@link Clock}; all time math is in whole-day units (the
 * curve's natural scale) computed from instants, so callers pass timestamps, not pre-computed deltas.
 */
public final class RetentionScorer {

    private static final double SECONDS_PER_DAY = 86_400.0;

    private final RetentionParameters params;
    private final Clock clock;

    /**
     * @param params the resolved decay knobs (from config #2); never null.
     * @param clock  the clock "now" is read from (UTC in production); never null. Injected so decay
     *     over elapsed time is testable without sleeping.
     */
    public RetentionScorer(RetentionParameters params, Clock clock) {
        if (params == null) {
            throw new IllegalArgumentException("retention params must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.params = params;
        this.clock = clock;
    }

    /** @return the parameters this scorer was built with (for diagnostics/tests). */
    public RetentionParameters parameters() {
        return params;
    }

    /**
     * Score a page's current retention against "now" using its default salience.
     *
     * @param layer          the page's retention layer; never null.
     * @param accessCount    recall-hit counter ({@code pages.access_count}); {@code >= 0}.
     * @param createdAt      when the page version was created; never null.
     * @param lastAccessedAt last recall access, or {@code null} if never accessed.
     * @return the retention score (higher = more worth keeping); always {@code >= 0}.
     */
    public double score(
            MemoryLayer layer, long accessCount, Instant createdAt, Instant lastAccessedAt) {
        return score(layer, params.defaultSalience(), accessCount, createdAt, lastAccessedAt);
    }

    /**
     * Score a page's current retention against "now".
     *
     * <p>The age term uses days since {@code createdAt}; the reinforcement term uses days since
     * {@code lastAccessedAt} (or since {@code createdAt} when the page has never been accessed, so a
     * brand-new page is treated as just-touched rather than infinitely stale on the access axis). Both
     * elapsed values are floored at zero, so a clock skew that puts a timestamp slightly in the future
     * cannot produce a score above the undecayed maximum.
     *
     * @param layer          the page's retention layer; never null.
     * @param salience       the page's intrinsic salience (use {@link RetentionParameters#defaultSalience()}
     *     when no explicit signal exists); {@code > 0}.
     * @param accessCount    recall-hit counter ({@code pages.access_count}); {@code >= 0}.
     * @param createdAt      when the page version was created; never null.
     * @param lastAccessedAt last recall access, or {@code null} if never accessed.
     * @return the retention score (higher = more worth keeping); always {@code >= 0}.
     */
    public double score(
            MemoryLayer layer,
            double salience,
            long accessCount,
            Instant createdAt,
            Instant lastAccessedAt) {
        if (layer == null) {
            throw new IllegalArgumentException("layer must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        if (accessCount < 0) {
            throw new IllegalArgumentException("accessCount must not be negative, was " + accessCount);
        }
        if (!(salience > 0) || !Double.isFinite(salience)) {
            throw new IllegalArgumentException("salience must be a finite value > 0, was " + salience);
        }

        Instant now = clock.instant();
        double ageDays = elapsedDays(createdAt, now);
        // Never accessed ⇒ measure the reinforcement decay from creation, not from the epoch.
        Instant accessAnchor = lastAccessedAt != null ? lastAccessedAt : createdAt;
        double daysSinceAccess = elapsedDays(accessAnchor, now);

        // Passive salience term — present only for age-decaying layers (semantic/procedural keep
        // their full salience regardless of calendar age).
        double salienceTerm =
                layer.ageDecays() ? salience * Math.exp(-params.lambda() * ageDays) : salience;

        // Active reinforcement term — diminishing in access count, relaxing since last access.
        double reinforcement =
                params.sigma() * Math.log1p(accessCount) * Math.exp(-params.mu() * daysSinceAccess);

        return salienceTerm + reinforcement;
    }

    /**
     * Whether a page is "cold" — its retention has decayed to at/below the configured threshold and
     * it is therefore a forget-sweep candidate (#25 owns the sweep <em>action</em>; this is only the
     * classification). A page in a layer that does not age out can still be cold if it was never
     * reinforced enough to clear the floor.
     *
     * @param layer          the page's retention layer; never null.
     * @param accessCount    recall-hit counter; {@code >= 0}.
     * @param createdAt      when the page version was created; never null.
     * @param lastAccessedAt last recall access, or {@code null} if never accessed.
     * @return {@code true} if the current score is {@code <= coldThreshold}.
     */
    public boolean isCold(
            MemoryLayer layer, long accessCount, Instant createdAt, Instant lastAccessedAt) {
        return score(layer, accessCount, createdAt, lastAccessedAt) <= params.coldThreshold();
    }

    /** Whole (fractional) days between two instants, floored at zero (never negative). */
    private static double elapsedDays(Instant from, Instant to) {
        double seconds = Duration.between(from, to).toNanos() / 1_000_000_000.0;
        if (seconds <= 0) {
            return 0.0;
        }
        return seconds / SECONDS_PER_DAY;
    }
}
