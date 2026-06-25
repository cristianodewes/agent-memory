package com.agentmemory.llm;

import java.util.List;

/**
 * The pluggable embeddings provider — a <strong>separate, default-on axis</strong> from
 * {@link LlmProvider} (DD-005). Embeddings power the {@code pgvector} half of hybrid recall; if they
 * are unavailable, recall degrades gracefully to FTS + graph, so the startup probe for this axis is
 * <em>non-fatal</em> (a logged degraded-recall warning), unlike the required chat provider.
 *
 * <p>Anthropic does not offer an embeddings endpoint, so the embedder is genuinely a different
 * provider from the chat LLM (Voyage AI is the recommended default; OpenAI-compatible endpoints fit
 * here too). That separation is exactly why {@link com.agentmemory.config.AgentMemoryProperties}
 * carries independent {@code llm} and {@code embeddings} groups.
 *
 * <p>Every result carries the denormalized {@code {provider, model, dim}} triple (invariant #8).
 * {@link #dimensions()} is the configured contract the #4 schema's {@code pgvector} column width
 * must match; see {@link EmbeddingResult}.
 */
public interface Embedder {

    /**
     * Embed a single text.
     *
     * @param text the input; must be non-blank.
     * @return the embedding with its denormalized {@code {provider, model, dim}}.
     * @throws LlmException on a provider/transport failure or a dimension-contract violation.
     */
    EmbeddingResult embed(String text);

    /**
     * Embed a batch of texts in input order. The default implementation embeds one at a time;
     * providers with a native batch endpoint should override for efficiency.
     *
     * @param texts the inputs; must be non-empty and individually non-blank.
     * @return one {@link EmbeddingResult} per input, in the same order.
     * @throws LlmException on a provider/transport failure.
     */
    default List<EmbeddingResult> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Embedder.embedAll requires a non-empty list");
        }
        return texts.stream().map(this::embed).toList();
    }

    /** The provider key this instance serves (e.g. {@code voyage}); the denormalized {@code provider}. */
    String id();

    /** The embedding model id, resolved from config (e.g. {@code voyage-3}). */
    String model();

    /**
     * The fixed dimensionality this embedder produces — the contract the persistence layer relies on
     * to size the {@code pgvector} column (#4) and to guard against mixing incompatible vectors.
     * Every {@link EmbeddingResult} from this embedder must have {@code dim == dimensions()}.
     */
    int dimensions();

    /**
     * Cheap liveness/credential probe run once by the startup health gate. Because embeddings are an
     * optional axis (DD-005), a failure is logged as a degraded-recall warning rather than aborting
     * startup. Must throw {@link LlmException} on failure so the gate can report it.
     *
     * @throws LlmException if the provider is unreachable or rejects the credentials.
     */
    void probe();
}
