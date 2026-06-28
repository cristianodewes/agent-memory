package com.agentmemory.recall;

import com.agentmemory.core.MemoryLayer;

/**
 * The resolved, immutable per-layer half-lives of the recall recency prior (issue #140) — the
 * plain-value view {@link RecencyDecay} computes against, decoupled from Spring binding. The
 * {@code recall}-base config ({@link RecencyProperties}) is the source and adapts into this type via
 * {@link RecencyProperties#toParameters()} so the decay math and its tests carry no config dependency.
 *
 * <p>A half-life is the page age (in days, since {@code pages.updated_at}) at which the recency factor
 * {@code exp(−ln2 · ageDays / halfLife)} reaches {@code 0.5}. Only the age-decaying layers
 * ({@link MemoryLayer#WORKING}, {@link MemoryLayer#EPISODIC}) carry one — the recency prior mirrors
 * {@link com.agentmemory.store.RetentionScorer} in leaving {@link MemoryLayer#SEMANTIC} /
 * {@link MemoryLayer#PROCEDURAL} undecayed ({@link MemoryLayer#ageDecays()} is {@code false}), so
 * timeless and procedural knowledge keeps its full relevance regardless of calendar age. A half-life
 * of {@code 0} disables decay for that layer (factor stays {@code 1.0}).
 *
 * @param workingHalfLifeDays  half-life (days) for {@link MemoryLayer#WORKING} pages; {@code >= 0}.
 * @param episodicHalfLifeDays half-life (days) for {@link MemoryLayer#EPISODIC} pages; {@code >= 0}.
 */
public record RecencyParameters(double workingHalfLifeDays, double episodicHalfLifeDays) {

    public RecencyParameters {
        requireNonNegative("workingHalfLifeDays", workingHalfLifeDays);
        requireNonNegative("episodicHalfLifeDays", episodicHalfLifeDays);
    }

    /** The documented defaults (mirrors {@link RecencyProperties}); handy for tests. */
    public static RecencyParameters defaults() {
        return new RecencyParameters(1.0, 14.0);
    }

    /**
     * The half-life in days to apply to a page in {@code layer}, or {@code 0} when the layer does not
     * age-decay (semantic/procedural) — in which case {@link RecencyDecay} leaves the score untouched.
     *
     * @param layer the page's retention layer; never null.
     * @return the per-layer half-life in days ({@code 0} ⇒ no age decay for that layer).
     */
    public double halfLifeDays(MemoryLayer layer) {
        if (layer == null) {
            throw new IllegalArgumentException("layer must not be null");
        }
        return switch (layer) {
            case WORKING -> workingHalfLifeDays;
            case EPISODIC -> episodicHalfLifeDays;
            // Timeless / procedural knowledge does not fade on the calendar (ageDecays() == false).
            case SEMANTIC, PROCEDURAL -> 0.0;
        };
    }

    private static void requireNonNegative(String name, double value) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "recency " + name + " must be a finite value >= 0, was " + value);
        }
    }
}
