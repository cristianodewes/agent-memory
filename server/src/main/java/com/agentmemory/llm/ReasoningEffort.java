package com.agentmemory.llm;

/**
 * An optional, provider-neutral hint on a {@link ChatRequest} asking the model to spend <em>less</em>
 * hidden reasoning on a call (issue #130, Fase 1). It exists for the latency-sensitive recall path:
 * the rerank/expansion steps emit ~20 tokens of structured JSON but, at the default reasoning effort, a
 * reasoning model burns hundreds/thousands of hidden reasoning tokens first — the dominant term in the
 * {@code /recall/inject} latency that blows the client's deadline.
 *
 * <p>Like {@link ChatRequest#schema()} this is a domain-level hint: a {@code null} effort means
 * "unchanged behavior" and only the OpenAI ChatGPT/Codex OAuth provider acts on a non-null value (its
 * Responses backend is the one that accepts the {@code reasoning.effort} / {@code text.verbosity}
 * params — they are the Codex CLI's own knobs). Other providers ignore it, so callers may set it freely
 * without coupling to a provider. The model itself is still never on the request (it is provider-config,
 * invariant) — this only tunes how hard that fixed model thinks.
 */
public enum ReasoningEffort {

    /** The lowest setting: near-zero hidden reasoning. Used by the recall steps for speed. */
    MINIMAL("minimal", "low"),

    /** A small amount of hidden reasoning. */
    LOW("low", "low"),

    /** The provider's default reasoning budget. */
    MEDIUM("medium", "medium"),

    /** Maximum hidden reasoning (slowest). */
    HIGH("high", "high");

    private final String wire;
    private final String verbosity;

    ReasoningEffort(String wire, String verbosity) {
        this.wire = wire;
        this.verbosity = verbosity;
    }

    /** The provider wire value for the {@code reasoning.effort} field (e.g. {@code "minimal"}). */
    public String wire() {
        return wire;
    }

    /**
     * The matching {@code text.verbosity} value — a terser reply for a lower-effort call. Emitted only
     * when the request asks for structured output (the {@code text} node already exists then).
     */
    public String verbosity() {
        return verbosity;
    }
}
