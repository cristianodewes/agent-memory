package com.agentmemory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure unit test (no DB) for the pgvector literal encoder in {@link PageEmbeddingStore}. The literal
 * is what crosses the wire in place of a third-party pgvector binding (#16), so a malformed encoding
 * would silently corrupt every stored/queried vector — worth pinning directly.
 */
class PageEmbeddingStoreEncodingTest {

    @Test
    void encodesComponentsInBracketedCommaSeparatedOrder() {
        assertThat(PageEmbeddingStore.toVectorLiteral(new float[] {1.0f, -2.5f, 0.0f}, false))
                .isEqualTo("[1.0,-2.5,0.0]");
    }

    @Test
    void encodesASingleComponent() {
        assertThat(PageEmbeddingStore.toVectorLiteral(new float[] {0.25f}, false)).isEqualTo("[0.25]");
    }

    @Test
    void rejectsNonFiniteComponentsWhenFinitenessRequired() {
        // pgvector's parser rejects NaN/Infinity; the guard turns that into a clear error rather than
        // an opaque CAST DataAccessException (a degenerate embedder is skipped loudly, not stored).
        assertThatThrownBy(() ->
                PageEmbeddingStore.toVectorLiteral(new float[] {1.0f, Float.NaN, 3.0f}, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not finite");
        assertThatThrownBy(() ->
                PageEmbeddingStore.toVectorLiteral(new float[] {Float.POSITIVE_INFINITY}, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contractDimensionIsTheV7ColumnWidth() {
        assertThat(PageEmbeddingStore.EMBEDDING_DIM).isEqualTo(1024);
    }
}
