package com.agentmemory.store;

/**
 * The resolved, immutable knobs of the retention formula (issue #24) — the plain-value view the
 * {@link RetentionScorer} computes against, decoupled from Spring binding. The single config (#2,
 * {@code AgentMemoryProperties.Decay}) is the source; {@link StoreConfiguration} adapts it into this
 * type so the scorer and its tests have no dependency on the config module.
 *
 * <p>Constants of
 * <pre>  score = salience·exp(−λ·Δt_days) + σ·log(1+access_count)·exp(−μ·days_since_access)</pre>
 *
 * @param lambda          age-decay rate λ per day (salience term); {@code >= 0}.
 * @param sigma           weight σ of the access-reinforcement term; {@code >= 0}.
 * @param mu              decay rate μ per day on time since last access; {@code >= 0}.
 * @param defaultSalience baseline salience when a page has no explicit salience signal; {@code > 0}.
 * @param coldThreshold   score at/below which a page is "cold" (a sweep candidate, #25); {@code >= 0}.
 */
public record RetentionParameters(
        double lambda, double sigma, double mu, double defaultSalience, double coldThreshold) {

    public RetentionParameters {
        requireNonNegative("lambda", lambda);
        requireNonNegative("sigma", sigma);
        requireNonNegative("mu", mu);
        requireNonNegative("coldThreshold", coldThreshold);
        if (!(defaultSalience > 0) || !Double.isFinite(defaultSalience)) {
            throw new IllegalArgumentException(
                    "retention defaultSalience must be a finite value > 0, was " + defaultSalience);
        }
    }

    /** The documented defaults (mirrors {@code AgentMemoryProperties.Decay}); handy for tests. */
    public static RetentionParameters defaults() {
        return new RetentionParameters(0.02, 1.0, 0.01, 1.0, 0.05);
    }

    private static void requireNonNegative(String name, double value) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "retention " + name + " must be a finite value >= 0, was " + value);
        }
    }
}
