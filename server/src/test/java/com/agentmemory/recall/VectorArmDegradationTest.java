package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.PageId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.store.PageEmbeddingStore;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (no DB) for the #16 graceful-degradation fast paths — the branches that must short
 * circuit <em>before</em> any database access so recall is never coupled to embeddings availability
 * (DD-005). Each of these returns without touching the {@link PageEmbeddingStore}, so a store built
 * over a {@code null} template is safe and proves the no-DB-touch property by construction.
 */
class VectorArmDegradationTest {

    /** A store whose JdbcTemplate is never dereferenced on the disabled paths under test. */
    private static final PageEmbeddingStore NO_DB = new PageEmbeddingStore(null);

    private static PageId someId() {
        return new PageId(Uuid7.randomUuid());
    }

    // --- VectorArm.enabled() / rank() short-circuits -----------------------------------------------

    @Test
    void armIsDisabledAndRanksEmptyWithNoEmbedder() {
        VectorArm arm = new VectorArm(NO_DB, null);
        assertThat(arm.enabled()).isFalse();
        assertThat(arm.rank("ws", "proj", "anything", 10).isEmpty()).isTrue();
    }

    @Test
    void armIsDisabledOnWidthMismatchAndDoesNotTouchTheStore() {
        // 8-dim embedder vs the 1024-dim column → disabled; rank() returns empty without a DB call.
        VectorArm arm = new VectorArm(NO_DB, ScriptedEmbedder.wrongWidth(8));
        assertThat(arm.enabled()).isFalse();
        assertThat(arm.rank("ws", "proj", "query", 10).isEmpty()).isTrue();
    }

    @Test
    void armSwallowsAnUnreachableEmbedderAndRanksEmpty() {
        // Embedder is contract-width (so enabled()) but throws on embed → rank() degrades to empty.
        VectorArm arm = new VectorArm(NO_DB, ScriptedEmbedder.unreachable());
        assertThat(arm.enabled()).isTrue();
        assertThat(arm.rank("ws", "proj", "query", 10).isEmpty()).isTrue();
    }

    @Test
    void armSwallowsAVectorQueryFailureAndRanksEmpty() {
        // The embedder is healthy (enabled, embeds fine) but the pgvector nearest-neighbour query
        // throws — e.g. a missing extension/index or a statement timeout, decoupled from the FTS arm.
        // The whole arm must degrade to empty, NOT propagate, so recall falls back to FTS + graph.
        PageEmbeddingStore failingStore = new PageEmbeddingStore(null) {
            @Override
            public java.util.List<VectorHit> nearestLatest(
                    String workspace, String project, float[] queryVector,
                    String provider, String model, int limit) {
                throw new org.springframework.dao.QueryTimeoutException("vector query timed out");
            }
        };
        VectorArm arm = new VectorArm(failingStore, ScriptedEmbedder.contractWidth());
        assertThat(arm.enabled()).isTrue();
        assertThat(arm.rank("ws", "proj", "query", 10).isEmpty()).isTrue(); // degraded, did not throw
    }

    @Test
    void armRanksEmptyForBlankQuery() {
        VectorArm arm = new VectorArm(NO_DB, ScriptedEmbedder.contractWidth());
        assertThat(arm.rank("ws", "proj", "   ", 10).isEmpty()).isTrue();
    }

    // --- PageEmbeddingService skip paths -----------------------------------------------------------

    @Test
    void embedPageSkipsWhenNoEmbedderConfigured() {
        PageEmbeddingService svc = new PageEmbeddingService(NO_DB, null);
        assertThat(svc.embeddingsEnabled()).isFalse();
        assertThat(svc.embedPage(someId(), "title", "body")).isFalse();
    }

    @Test
    void embedPageSkipsOnWidthMismatchWithoutTouchingTheStore() {
        PageEmbeddingService svc = new PageEmbeddingService(NO_DB, ScriptedEmbedder.wrongWidth(8));
        assertThat(svc.embeddingsEnabled()).isFalse();
        assertThat(svc.embedPage(someId(), "title", "body")).isFalse();
    }

    @Test
    void embedPageSkipsOnUnreachableEmbedder() {
        PageEmbeddingService svc = new PageEmbeddingService(NO_DB, ScriptedEmbedder.unreachable());
        // Contract width, so embeddingsEnabled() is true, but the embed call throws → graceful false.
        assertThat(svc.embeddingsEnabled()).isTrue();
        assertThat(svc.embedPage(someId(), "title", "body")).isFalse();
    }

    @Test
    void embedPageSkipsAnEmptyPage() {
        PageEmbeddingService svc = new PageEmbeddingService(NO_DB, ScriptedEmbedder.contractWidth());
        // No title and no body → nothing to embed; a graceful false, not a degradation.
        assertThat(svc.embedPage(someId(), "  ", "  ")).isFalse();
    }

    // --- argument guards ---------------------------------------------------------------------------

    @Test
    void constructorsRejectNullStore() {
        assertThatThrownBy(() -> new VectorArm(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageEmbeddingService(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
