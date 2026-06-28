package com.agentmemory.recall;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Recency-prior tuning for the base hybrid recall (issue #140), bound under
 * {@code agent-memory.recall.recency}. Deliberately a {@code recall}-base binding (a sibling of the
 * {@code agent-memory.recall.llm} {@link com.agentmemory.llmrecall.LlmRecallProperties}) rather than
 * part of the LLM-recall config: the prior is applied in the {@code recall} fusion, below the LLM
 * decorator, so it owns its own knobs there.
 *
 * <p>The prior multiplies each fused hit's score by a per-layer exponential decay of its age (since
 * {@code pages.updated_at}); see {@link RecencyDecay}. Half-lives are per layer because a volatile
 * working note and a multi-week episodic digest should not fade at the same rate. Semantic and
 * procedural pages do not age-decay at all (mirroring {@link com.agentmemory.store.RetentionScorer} /
 * {@link com.agentmemory.core.MemoryLayer#ageDecays()}), so they need no knob here.
 *
 * <p><strong>Reindex caveat.</strong> {@code pages.updated_at} is stamped on every version insert and
 * a full reindex re-creates every page, so after a full rebuild recency reflects the rebuild time, not
 * the real edit. The prior is intentionally gentle (and disable-able) for that reason; an incremental
 * reindex is idempotent and does not re-stamp unchanged pages.
 *
 * @param enabled              master switch for the recency prior; {@code false} leaves recall as the
 *     untouched base RRF order. Default {@code true}.
 * @param workingHalfLifeDays  half-life (days) for {@link com.agentmemory.core.MemoryLayer#WORKING}
 *     pages — session scratch fades fast. {@code 0} disables decay for the layer. Must be {@code >= 0}.
 *     Default {@code 1.0}.
 * @param episodicHalfLifeDays half-life (days) for {@link com.agentmemory.core.MemoryLayer#EPISODIC}
 *     pages — events fade over a couple of weeks. {@code 0} disables decay for the layer. Must be
 *     {@code >= 0}. Default {@code 14.0}.
 */
@ConfigurationProperties(prefix = "agent-memory.recall.recency")
public record RecencyProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1.0") double workingHalfLifeDays,
        @DefaultValue("14.0") double episodicHalfLifeDays) {

    public RecencyProperties {
        requireNonNegative("working-half-life-days", workingHalfLifeDays);
        requireNonNegative("episodic-half-life-days", episodicHalfLifeDays);
    }

    /** Adapt the bound config into the plain {@link RecencyParameters} the decay math consumes. */
    public RecencyParameters toParameters() {
        return new RecencyParameters(workingHalfLifeDays, episodicHalfLifeDays);
    }

    private static void requireNonNegative(String key, double value) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "agent-memory.recall.recency." + key + " must be a finite value >= 0, was " + value);
        }
    }
}
