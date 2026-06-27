package com.agentmemory.llmrecall;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cost controls for LLM-assisted recall (issue #21), bound under {@code agent-memory.recall.llm}.
 * Kept as a focused, feature-local binding rather than swelling the central
 * {@link com.agentmemory.config.AgentMemoryProperties} (which is deliberately thin): this module owns
 * its own tuning, exactly as the sanitizer and ingest queue own theirs.
 *
 * <p>These knobs are the "cost controls cap K and calls, with a non-LLM fast path when over budget"
 * the acceptance asks for. Every LLM step is bounded by a candidate cap ({@code K}) and a per-query
 * call budget; when a query would exceed the budget, or the LLM is unavailable, recall falls back to
 * the raw RRF order with no model call (the fast path).
 *
 * <p><strong>Latency budget (issue #130).</strong> {@code budgetMs} caps the wall-clock the LLM steps
 * may spend before {@link LlmRecallService} abandons them and returns the already-computed hybrid (RRF)
 * result — so {@code /recall/inject}, which fires on every prompt and is bounded by the client's 10s
 * deadline, degrades to the fast hybrid result instead of the client timing out with nothing. The
 * remaining budget is also enforced as a real per-call HTTP timeout (not a post-hoc check), and
 * {@code minimalReasoning} cuts the dominant hidden-reasoning latency of a reasoning model on the
 * recall calls only.
 *
 * @param enabled         master switch for the rerank decorator; when {@code false} recall is the
 *     untouched RRF base (the fast path is always taken). Default {@code true}.
 * @param maxCandidates   the hard cap K on how many fused candidates are ever sent to the LLM to be
 *     re-scored; a query with more candidates re-ranks only its top K and keeps the RRF tail. Bounds
 *     prompt size and cost. Must be {@code > 0}. Default 8 (issue #130 Fase 0: down from 20 — the
 *     injection block renders at most 5, so re-scoring 8 is plenty and 60% cheaper).
 * @param maxCallsPerQuery the per-query ceiling on LLM calls across expansion + rerank; the pipeline
 *     spends its budget on rerank first (the higher-value step). {@code 0} disables all LLM work
 *     (pure fast path). Default 2 (one expansion + one rerank). Must be {@code >= 0}.
 * @param minRerankCandidates the smallest fused-candidate count worth paying the LLM to re-order; at
 *     or below this, RRF order already suffices and the rerank call is skipped. Must be {@code > 0}.
 *     Default 2.
 * @param budgetMs         the wall-clock budget (ms) the LLM steps may spend before being abandoned in
 *     favour of the hybrid result; also the ceiling for the recall calls' per-call HTTP timeout. Set
 *     comfortably under the client's 10s deadline. Must be {@code > 0}. Default 6000.
 * @param minimalReasoning whether the recall LLM calls request {@link com.agentmemory.llm.ReasoningEffort#MINIMAL}
 *     reasoning effort (issue #130 Fase 1) — the lever that cuts a reasoning model's hidden-reasoning
 *     latency. Default {@code true}; flip to {@code false} if a backend rejects the {@code reasoning}
 *     param (the rerank then degrades safely to RRF order regardless).
 * @param expansion       query-expansion sub-settings.
 * @param injection       curated-injection sub-settings.
 */
@ConfigurationProperties(prefix = "agent-memory.recall.llm")
public record LlmRecallProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("8") int maxCandidates,
        @DefaultValue("2") int maxCallsPerQuery,
        @DefaultValue("2") int minRerankCandidates,
        @DefaultValue("6000") long budgetMs,
        @DefaultValue("true") boolean minimalReasoning,
        @DefaultValue Expansion expansion,
        @DefaultValue Injection injection) {

    public LlmRecallProperties {
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("maxCandidates must be > 0, was " + maxCandidates);
        }
        if (maxCallsPerQuery < 0) {
            throw new IllegalArgumentException("maxCallsPerQuery must be >= 0, was " + maxCallsPerQuery);
        }
        if (minRerankCandidates <= 0) {
            throw new IllegalArgumentException(
                    "minRerankCandidates must be > 0, was " + minRerankCandidates);
        }
        if (budgetMs <= 0) {
            throw new IllegalArgumentException("budgetMs must be > 0, was " + budgetMs);
        }
        if (expansion == null) {
            expansion = new Expansion(false, 4);
        }
        if (injection == null) {
            injection = new Injection(5, 0.4, 1200);
        }
    }

    /**
     * Query-expansion tuning.
     *
     * @param enabled   whether to spend an LLM call rewriting the query into extra retrieval terms
     *     before fusion. Default {@code false} (issue #130 Fase 0): appending terms to the conjunctive
     *     {@code plainto_tsquery} only narrows the FTS arm and dilutes the embedding centroid — it is an
     *     IR regression, not just a latency cost, so it is off by default. Re-enable per deployment only
     *     if a measured recall win justifies the extra LLM round-trip.
     * @param maxTerms  the cap on how many expansion terms are appended to the query text; bounds the
     *     widening of the FTS arm. Must be {@code > 0}. Default 4.
     */
    public record Expansion(@DefaultValue("false") boolean enabled, @DefaultValue("4") int maxTerms) {
        public Expansion {
            if (maxTerms <= 0) {
                throw new IllegalArgumentException("expansion.maxTerms must be > 0, was " + maxTerms);
            }
        }
    }

    /**
     * Curated-injection tuning — the bounded, relevance-gated block a {@code UserPromptSubmit} hook
     * pastes into context.
     *
     * @param maxHits  the hard cap on hits included in the injected block. Must be {@code > 0}.
     *     Default 5.
     * @param minScore the relevance gate, expressed as a fraction of the <em>top</em> hit's score: a
     *     hit is kept when {@code hit.score >= minScore * topScore}, and an all-below-gate result yields
     *     an empty block. Being relative to the best hit makes the gate scale-invariant — it works the
     *     same whether the scores are normalized LLM relevances (rerank ran) or raw RRF fused scores
     *     (rerank skipped). {@code 0.0} admits everything (and always keeps the top hit). Must be in
     *     {@code [0, 1]}. Default 0.4 (issue #130 Fase 0: a relative gate that suppresses weak bullets on
     *     low-signal prompts; an <em>absolute</em> gate waits on the calibrated cross-encoder in Fase 2).
     * @param maxChars the hard upper bound on the rendered block length; the block is truncated to fit
     *     so a hook can rely on a bounded paste. Must be {@code > 0}. Default 1200.
     */
    public record Injection(
            @DefaultValue("5") int maxHits,
            @DefaultValue("0.4") double minScore,
            @DefaultValue("1200") int maxChars) {
        public Injection {
            if (maxHits <= 0) {
                throw new IllegalArgumentException("injection.maxHits must be > 0, was " + maxHits);
            }
            if (minScore < 0.0 || minScore > 1.0) {
                throw new IllegalArgumentException(
                        "injection.minScore must be in [0,1], was " + minScore);
            }
            if (maxChars <= 0) {
                throw new IllegalArgumentException("injection.maxChars must be > 0, was " + maxChars);
            }
        }
    }
}
