package com.agentmemory.llm;

/**
 * A single text embedding: the dense {@code vector} plus the {@code provider} / {@code model} /
 * {@code dim} that produced it — the denormalized triple cross-cutting invariant #8 requires to sit
 * next to every embedding ("{@code {provider, model, dim}} denormalized next to every embedding").
 *
 * <p>{@link #dim()} is derived from the vector length and re-asserted in the constructor, so a
 * provider that silently returns a wrong-width vector fails loudly here rather than corrupting the
 * {@code page_embeddings} table. The persistence layer (#4 schema, #14 reindex) stores
 * {@code (provider, model, dim)} alongside the vector and uses {@code dim} to guard the
 * {@code pgvector} column width — see {@link Embedder#dimensions()} for the configured contract.
 *
 * @param vector   the embedding components; must be non-empty.
 * @param provider the provider key that produced it (e.g. {@code voyage}).
 * @param model    the embedding model id (e.g. {@code voyage-3}).
 * @param dim      the vector dimensionality; must equal {@code vector.length}.
 */
public record EmbeddingResult(float[] vector, String provider, String model, int dim) {

    public EmbeddingResult {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("EmbeddingResult.vector must be non-empty");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("EmbeddingResult.provider must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("EmbeddingResult.model must not be blank");
        }
        if (dim != vector.length) {
            throw new IllegalArgumentException(
                    "EmbeddingResult.dim (" + dim + ") must equal vector length (" + vector.length + ")");
        }
        vector = vector.clone();
    }

    /** Build a result, deriving {@code dim} from the vector length. */
    public static EmbeddingResult of(float[] vector, String provider, String model) {
        return new EmbeddingResult(vector, provider, model, vector == null ? 0 : vector.length);
    }

    @Override
    public float[] vector() {
        // Defensive copy on the way out too: the vector is mutable and flows into storage.
        return vector.clone();
    }

    @Override
    public String toString() {
        // Never dump the full vector into logs; the triple is the useful, bounded part.
        return "EmbeddingResult[provider=" + provider + ", model=" + model + ", dim=" + dim + "]";
    }
}
