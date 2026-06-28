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
 * @param crossEncoder    calibrated cross-encoder rerank sub-settings (issue #130 Fase 2).
 * @param mmr             MMR diversity sub-settings (issue #141 Fase 4).
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
        @DefaultValue CrossEncoder crossEncoder,
        @DefaultValue Mmr mmr,
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
        if (crossEncoder == null) {
            crossEncoder = new CrossEncoder(true, CrossEncoder.DEFAULT_MODEL, 50);
        }
        if (mmr == null) {
            mmr = new Mmr(true, Mmr.DEFAULT_LAMBDA);
        }
        if (expansion == null) {
            expansion = new Expansion(false, 4);
        }
        if (injection == null) {
            injection = new Injection(5, 0.4, 0.35, 1200, null);
        }
    }

    /**
     * Calibrated cross-encoder rerank tuning (issue #130 Fase 2). The cross-encoder (Voyage
     * {@code rerank-2-lite}) is a fast, non-generative reranker whose score is calibrated enough to
     * drive an absolute relevance gate; it stands in front of the generative LLM reranker and degrades
     * to it when Voyage is absent or fails. It is wired only when the <em>embeddings</em> provider is
     * Voyage with an API key (it reuses that same key, DD-005); otherwise the LLM reranker stays.
     *
     * @param enabled      master switch for the cross-encoder rerank. Default {@code true} (it still
     *     only activates when Voyage embeddings auth is present); {@code false} forces the LLM reranker.
     * @param model        the rerank model id. Default {@value CrossEncoder#DEFAULT_MODEL}.
     * @param maxDocuments the hard cap on how many candidates the cross-encoder scores in one call —
     *     the rerank head is {@code min(maxDocuments, fused pool)}, and since the pool is over-fetched to
     *     {@code max(query.limit, maxCandidates)} this right-sizes K to the request (cheap reranker, so it
     *     can score the whole pool). Must be {@code > 0}. Default 50.
     */
    public record CrossEncoder(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("rerank-2-lite") String model,
            @DefaultValue("50") int maxDocuments) {

        /** Default cross-encoder model — Voyage's fast, cheap reranker. */
        public static final String DEFAULT_MODEL = "rerank-2-lite";

        public CrossEncoder {
            if (model == null || model.isBlank()) {
                model = DEFAULT_MODEL;
            }
            if (maxDocuments <= 0) {
                throw new IllegalArgumentException(
                        "cross-encoder.maxDocuments must be > 0, was " + maxDocuments);
            }
        }
    }

    /**
     * MMR (Maximal Marginal Relevance) diversity tuning (issue #141, Fase 4). After the rerank and
     * before the trim, an MMR pass re-orders the candidates to maximize marginal information
     * (relevance − redundancy) over their embeddings, so the injected block is not several bullets of
     * one topic. It is pure CPU over the ≤K candidate vectors plus one bounded embeddings fetch (no
     * network), so it is <em>not</em> bounded by the LLM time budget; it degrades to the unchanged
     * rerank order when embeddings are absent and only ever re-orders (never rescoring a hit), so the
     * {@code calibrated} flag flows through.
     *
     * @param enabled whether to run the MMR diversity pass. Default {@code true}: it is cheap (cosine
     *     over ≤K vectors + one indexed by-id query) and has an effect <em>only</em> when embeddings
     *     are present — the same axis the cross-encoder rerank (also default-on) needs — so it
     *     activates exactly where the "5 bullets, same topic" problem (#130) appears and is a no-op
     *     everywhere else. Set {@code false} to keep the pure rerank order.
     * @param lambda  the relevance/diversity trade-off in {@code [0, 1]}:
     *     {@code next = argmax[ λ·rel(c) − (1−λ)·max_{s∈selected} cos(c, s) ]}. Higher favours
     *     relevance, lower favours diversity. Must be in {@code [0, 1]}. Default
     *     {@value #DEFAULT_LAMBDA} (relevance-leaning — a conservative re-order that promotes a diverse
     *     candidate only when redundancy is high).
     */
    public record Mmr(@DefaultValue("true") boolean enabled, @DefaultValue("0.7") double lambda) {

        /** Default λ — relevance-leaning, so the diversity re-order stays conservative. */
        public static final double DEFAULT_LAMBDA = 0.7;

        public Mmr {
            if (lambda < 0.0 || lambda > 1.0) {
                throw new IllegalArgumentException("mmr.lambda must be in [0,1], was " + lambda);
            }
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
     *     low-signal prompts).
     * @param minScoreAbsolute the absolute relevance floor (issue #130 Fase 2), applied <em>only</em>
     *     when a calibrated cross-encoder produced the scores: if even the top hit scores below it, the
     *     block is empty (the calibrated "nothing relevant here" on a low-signal prompt). It is never
     *     applied to uncalibrated RRF/LLM scores, whose tiny magnitudes an absolute cut would wrongly
     *     empty. Must be in {@code [0, 1]}. Default 0.35.
     * @param maxChars the hard upper bound on the rendered block length; the block is truncated to fit
     *     so a hook can rely on a bounded paste. Must be {@code > 0}. Default 1200.
     * @param brief    synthesized-brief sub-settings (issue #135, Fase 3).
     */
    public record Injection(
            @DefaultValue("5") int maxHits,
            @DefaultValue("0.4") double minScore,
            @DefaultValue("0.35") double minScoreAbsolute,
            @DefaultValue("1200") int maxChars,
            @DefaultValue Brief brief) {
        public Injection {
            if (maxHits <= 0) {
                throw new IllegalArgumentException("injection.maxHits must be > 0, was " + maxHits);
            }
            if (minScore < 0.0 || minScore > 1.0) {
                throw new IllegalArgumentException(
                        "injection.minScore must be in [0,1], was " + minScore);
            }
            if (minScoreAbsolute < 0.0 || minScoreAbsolute > 1.0) {
                throw new IllegalArgumentException(
                        "injection.minScoreAbsolute must be in [0,1], was " + minScoreAbsolute);
            }
            if (maxChars <= 0) {
                throw new IllegalArgumentException("injection.maxChars must be > 0, was " + maxChars);
            }
            if (brief == null) {
                brief = new Brief(false, Brief.DEFAULT_TIMEOUT_MS);
            }
        }

        /**
         * Synthesized-brief tuning (issue #135, Fase 3). When enabled <em>and</em> the relevance gate
         * approved on calibrated cross-encoder scores, the injection makes one minimal-effort generative
         * call to replace the raw snippet bullets with a short "what you need to know" paragraph plus
         * path citations. The call is the only generative step the injection hot path makes, so it is
         * bounded by a per-call timeout and degrades to the bullets on any failure or timeout.
         *
         * @param enabled   whether to synthesize the brief at all. Default {@code false}: the brief
         *     reintroduces a generative call (latency + Codex cost) and a prompt-injection surface, so it
         *     is opt-in until validated in production; when {@code false} the injection renders the
         *     existing bullets, unchanged.
         * @param timeoutMs the per-call HTTP timeout (ms) bounding the single brief call; on timeout the
         *     injection falls back to the bullets. Set comfortably under the client's deadline minus the
         *     recall search already spent (the brief only fires after a fast calibrated search). Must be
         *     {@code > 0}. Default {@value #DEFAULT_TIMEOUT_MS}.
         */
        public record Brief(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("4000") long timeoutMs) {

            /** Default per-call brief timeout (ms) — comfortably under the client's 10s deadline. */
            public static final long DEFAULT_TIMEOUT_MS = 4000;

            public Brief {
                if (timeoutMs <= 0) {
                    throw new IllegalArgumentException(
                            "injection.brief.timeoutMs must be > 0, was " + timeoutMs);
                }
            }
        }
    }
}
