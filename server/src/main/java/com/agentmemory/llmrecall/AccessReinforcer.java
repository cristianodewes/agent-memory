package com.agentmemory.llmrecall;

import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.Scope;
import java.util.List;

/**
 * Records that a set of recall hits were actually <em>returned</em> to a caller, so the decay model
 * (#24) can reinforce frequently-recalled pages. Issue #21's acceptance asks that "access
 * reinforcement (#24) fires on returned hits"; this interface is the seam between the two: recall
 * calls {@link #reinforce} with the final, post-rerank hits, and an implementation bumps the
 * {@code pages.access_count} / {@code pages.last_accessed_at} columns (already present in the V3
 * schema) that #24's decay math reads.
 *
 * <p>It is deliberately a <strong>pluggable, optional</strong> collaborator. {@link NoOpAccessReinforcer}
 * is the safe default (recall works with no reinforcement at all); {@link JdbcAccessReinforcer} is the
 * working additive bump wired when a {@link javax.sql.DataSource} is present. Because the bean is
 * declared {@code @ConditionalOnMissingBean}, the decay feature (#24) can later publish its own
 * reinforcer and take over without this module changing — the two never collide.
 *
 * <p><strong>Best-effort.</strong> Reinforcement is a side effect of a read; it must never fail the
 * recall it accompanies. Implementations swallow their own errors (logging at most) and return
 * normally, so a reinforcement hiccup degrades silently rather than turning a successful query into
 * an error.
 */
public interface AccessReinforcer {

    /**
     * Reinforce the pages behind the given returned hits within {@code scope}. Only
     * {@link com.agentmemory.recall.HitSource#PAGE} hits carry a page to reinforce; raw-observation
     * fallback hits have no page row and are ignored. Never throws.
     *
     * @param scope the {@code (workspace, project)} the hits belong to; never null.
     * @param hits  the final hits actually returned to the caller; never null (may be empty).
     */
    void reinforce(Scope scope, List<RecallHit> hits);
}
