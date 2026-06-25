package com.agentmemory.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The startup health gate for the provider layer (invariant #13, DD-005).
 *
 * <p>At boot it probes the two axes exactly once:
 * <ul>
 *   <li><strong>Chat LLM — required.</strong> {@link LlmProvider#probe()} must succeed; if it
 *       throws, {@link #verify()} re-throws an {@link LlmException} with an actionable message and
 *       startup aborts (fail-fast). There is no rule-based fallback to fall through to.</li>
 *   <li><strong>Embeddings — optional.</strong> {@link Embedder#probe()} failure is logged as a
 *       degraded-recall warning and startup continues; recall falls back to FTS + graph.</li>
 * </ul>
 *
 * <p>This class is framework-free so it can be unit-tested directly (a {@link TestDoubleProvider}
 * with {@code failProbe(true)} drives the fail-fast path with no network). {@code LlmModule} invokes
 * {@link #verify()} during context initialization so the probe runs before the server accepts
 * traffic.
 */
public final class LlmHealthGate {

    private static final Logger log = LoggerFactory.getLogger(LlmHealthGate.class);

    private final LlmProvider llmProvider;
    private final Embedder embedder;

    /**
     * @param llmProvider the required chat provider (never {@code null}).
     * @param embedder    the optional embedder, or {@code null} if embeddings are disabled entirely.
     */
    public LlmHealthGate(LlmProvider llmProvider, Embedder embedder) {
        if (llmProvider == null) {
            throw new IllegalArgumentException("llmProvider must not be null (the LLM is required)");
        }
        this.llmProvider = llmProvider;
        this.embedder = embedder;
    }

    /**
     * Run both probes. Returns normally when the required chat provider is reachable; throws to abort
     * startup when it is not.
     *
     * @throws LlmException if the required chat provider is unreachable or rejects its credentials.
     */
    public void verify() {
        verifyLlm();
        verifyEmbeddings();
    }

    private void verifyLlm() {
        log.info("Probing required LLM provider '{}' (model {}) at startup…",
                llmProvider.id(), llmProvider.model());
        try {
            llmProvider.probe();
        } catch (RuntimeException e) {
            // Fail-fast: the LLM is a required dependency (DD-005, invariant #13).
            throw new LlmException(
                    "Required LLM provider '" + llmProvider.id() + "' (model " + llmProvider.model()
                            + ") is unreachable or rejected its credentials at startup: " + e.getMessage()
                            + ". The server cannot start without a working LLM — check "
                            + "'agent-memory.llm.auth' (provider, api-key, base-url, model) and connectivity.",
                    e);
        }
        log.info("LLM provider '{}' is reachable.", llmProvider.id());
    }

    private void verifyEmbeddings() {
        if (embedder == null) {
            log.warn("No embeddings provider configured; recall will run on FTS + graph only "
                    + "(degraded semantic recall). Set 'agent-memory.embeddings.auth' to enable vectors.");
            return;
        }
        log.info("Probing embeddings provider '{}' (model {}, {} dims) at startup…",
                embedder.id(), embedder.model(), embedder.dimensions());
        try {
            embedder.probe();
            log.info("Embeddings provider '{}' is reachable ({} dims).", embedder.id(), embedder.dimensions());
        } catch (RuntimeException e) {
            // Non-fatal (DD-005): embeddings are a separate, default-on axis that may degrade.
            log.warn("Embeddings provider '{}' is unreachable at startup: {}. "
                            + "Continuing with degraded recall (FTS + graph); semantic/vector recall is "
                            + "disabled until it recovers.",
                    embedder.id(), e.getMessage());
        }
    }
}
