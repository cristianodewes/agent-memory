package com.agentmemory.recall;

import com.agentmemory.llm.Embedder;
import com.agentmemory.llm.EmbeddingResult;
import com.agentmemory.llm.LlmException;
import java.util.HashMap;
import java.util.Map;

/**
 * A deterministic, network-free {@link Embedder} for the #16 vector-recall tests that lets a test
 * pin <em>exactly</em> which vector a given text maps to. This is what makes "vector fusion improves
 * recall" assertable without a real embeddings provider: a test registers a target document's text →
 * vector and the query's text → the same (or near) vector, so the nearest-neighbour ordering is known
 * up front rather than emergent from a hash.
 *
 * <p>Unregistered text falls back to a stable hash-derived unit vector (so unrelated docs get
 * distinct, repeatable vectors). Width is configurable; the contract width is
 * {@value com.agentmemory.store.PageEmbeddingStore#EMBEDDING_DIM}. The probe can be made to fail and
 * {@link #embed} can be made to throw, to drive the graceful-degradation paths.
 */
final class ScriptedEmbedder implements Embedder {

    private final String id;
    private final String model;
    private final int dimensions;
    private final boolean failEmbed;
    private final Map<String, float[]> scripted = new HashMap<>();

    ScriptedEmbedder(String id, String model, int dimensions, boolean failEmbed) {
        this.id = id;
        this.model = model;
        this.dimensions = dimensions;
        this.failEmbed = failEmbed;
    }

    /** A healthy 1024-dim embedder (matches the page_embeddings column contract). */
    static ScriptedEmbedder contractWidth() {
        return new ScriptedEmbedder("voyage", "voyage-3",
                com.agentmemory.store.PageEmbeddingStore.EMBEDDING_DIM, false);
    }

    /** An embedder whose width does NOT match the contract (drives the dim-mismatch path). */
    static ScriptedEmbedder wrongWidth(int dimensions) {
        return new ScriptedEmbedder("voyage", "voyage-3", dimensions, false);
    }

    /** A contract-width embedder whose {@link #embed} throws (drives the unreachable path). */
    static ScriptedEmbedder unreachable() {
        return new ScriptedEmbedder("voyage", "voyage-3",
                com.agentmemory.store.PageEmbeddingStore.EMBEDDING_DIM, true);
    }

    /** Pin {@code text} to a specific vector (must be this embedder's width). Returns {@code this}. */
    ScriptedEmbedder map(String text, float[] vector) {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                    "scripted vector width " + vector.length + " != embedder dim " + dimensions);
        }
        scripted.put(text, vector.clone());
        return this;
    }

    @Override
    public EmbeddingResult embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("embed requires non-blank text");
        }
        if (failEmbed) {
            throw LlmException.permanent("scripted embedder configured to fail", null);
        }
        float[] v = scripted.getOrDefault(text, hashVector(text));
        return new EmbeddingResult(v, id, model, dimensions);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public void probe() {
        if (failEmbed) {
            throw LlmException.permanent("scripted embedder configured to fail", null);
        }
    }

    /** A stable, L2-normalized vector seeded from the text — distinct per input, repeatable. */
    private float[] hashVector(String text) {
        long state = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            state ^= (b & 0xff);
            state *= 0x100000001b3L;
        }
        float[] v = new float[dimensions];
        double sumSq = 0;
        for (int i = 0; i < dimensions; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            float c = ((state >>> 33) / (float) (1L << 31)) - 1.0f;
            v[i] = c;
            sumSq += (double) c * c;
        }
        float norm = (float) Math.sqrt(sumSq);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }
}
