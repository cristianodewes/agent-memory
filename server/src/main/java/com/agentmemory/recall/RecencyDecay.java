package com.agentmemory.recall;

import com.agentmemory.core.MemoryLayer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The recall <strong>recency prior</strong> (issue #140): a per-layer exponential decay factor in
 * {@code (0, 1]} that {@link RecencyDecayFusion} multiplies into a hit's fused score so that, all else
 * equal, a more recently updated page outranks a stale one. It deliberately mirrors
 * {@link com.agentmemory.store.RetentionScorer}'s age term and per-layer regime — the same curve shape
 * ({@code exp} of negative age), the same "semantic/procedural do not age-decay" rule
 * ({@link MemoryLayer#ageDecays()}), the same injectable {@link Clock} and whole-day units floored at
 * zero — but expressed as a per-layer <em>half-life</em> ({@link RecencyParameters}) rather than a
 * single rate, so working and episodic pages can fade at different speeds.
 *
 * <h2>Formula</h2>
 * <pre>  factor = exp(−ln2 · ageDays / halfLifeDays(layer))</pre>
 * with {@code ageDays} measured from {@code pages.updated_at} to "now". At {@code ageDays == halfLife}
 * the factor is exactly {@code 0.5}; at age {@code 0} (or for a non-decaying layer) it is {@code 1.0},
 * so the prior never <em>raises</em> a score — it only attenuates stale ones.
 *
 * <h2>No-op cases (factor {@code 1.0})</h2>
 * <ul>
 *   <li>A non-age-decaying layer — {@link MemoryLayer#SEMANTIC} / {@link MemoryLayer#PROCEDURAL}.</li>
 *   <li>A layer whose configured half-life is {@code 0} (decay disabled for it).</li>
 *   <li>An undatable hit ({@code updatedAt == null} or {@code layer == null}) — a raw-observation
 *       fallback or a vector-only hit carries no recency signal, so it is left unpenalized.</li>
 * </ul>
 *
 * <p><strong>Placement.</strong> This sits in the {@code recall} package and is applied
 * <em>pre-rerank</em> (before the Fase-2 cross-encoder): it reorders the fused candidate pool and the
 * fast-path order, and the cross-encoder — when it runs — re-scores the resulting head by pure
 * relevance. Recency and relevance therefore compose (pool by recency+relevance → top-K → rerank)
 * rather than the prior leaking into a calibrated relevance score. See {@link RecencyDecayFusion}.
 *
 * <p>Stateless and deterministic given a {@link Clock}; safe to share.
 */
public final class RecencyDecay {

    private static final double LN2 = Math.log(2.0);
    private static final double SECONDS_PER_DAY = 86_400.0;

    private final RecencyParameters params;
    private final Clock clock;

    /**
     * @param params the resolved per-layer half-lives; never null.
     * @param clock  the clock "now" is read from (UTC in production); never null. Injected so the
     *     decay over elapsed time is testable without sleeping.
     */
    public RecencyDecay(RecencyParameters params, Clock clock) {
        if (params == null) {
            throw new IllegalArgumentException("recency params must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.params = params;
        this.clock = clock;
    }

    /** @return the parameters this decay was built with (for diagnostics/tests). */
    public RecencyParameters parameters() {
        return params;
    }

    /**
     * The recency factor for a page in {@code layer} last updated at {@code updatedAt}, against "now".
     *
     * @param layer     the page's retention layer, or {@code null} for an undatable hit.
     * @param updatedAt the page version's update instant, or {@code null} for an undatable hit.
     * @return a multiplier in {@code (0, 1]} — {@code 1.0} when the hit does not decay (see class doc).
     */
    public double factor(MemoryLayer layer, Instant updatedAt) {
        // Undatable hit (raw-observation fallback / vector-only): no recency signal, so no penalty.
        if (layer == null || updatedAt == null) {
            return 1.0;
        }
        // Timeless / procedural knowledge keeps its full relevance regardless of calendar age.
        if (!layer.ageDecays()) {
            return 1.0;
        }
        double halfLife = params.halfLifeDays(layer);
        if (!(halfLife > 0)) {
            return 1.0; // decay disabled for this layer
        }
        double ageDays = elapsedDays(updatedAt, clock.instant());
        return Math.exp(-LN2 * ageDays / halfLife);
    }

    /** Whole (fractional) days between two instants, floored at zero (never negative on clock skew). */
    private static double elapsedDays(Instant from, Instant to) {
        double seconds = Duration.between(from, to).toNanos() / 1_000_000_000.0;
        if (seconds <= 0) {
            return 0.0;
        }
        return seconds / SECONDS_PER_DAY;
    }
}
