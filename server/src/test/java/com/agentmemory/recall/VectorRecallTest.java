package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.store.PageEmbeddingStore;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end vector-recall over a throwaway {@code pgvector/pgvector:pg16} (Testcontainers), proving
 * issue #16's acceptance criteria on a seeded corpus:
 *
 * <ul>
 *   <li><strong>Fusion improves recall</strong> — a page reachable <em>only</em> by semantic
 *       similarity (no shared FTS terms, no link) surfaces once the vector arm is fused in, and does
 *       <em>not</em> when recall is FTS + graph only.</li>
 *   <li><strong>Graceful degradation</strong> — with the embedder unavailable, recall still returns
 *       the FTS + graph results (no failure), and the vector arm is simply absent.</li>
 *   <li><strong>Dimension mismatch</strong> — an embedder whose width ≠ the column contract is
 *       detected: no vector is stored and the vector arm self-disables, recall continuing on FTS.</li>
 * </ul>
 *
 * <p>The arm and store are constructed directly against the container's {@link JdbcTemplate} so each
 * test pins its own {@link ScriptedEmbedder} (width, scripted vectors, failure) — the deterministic,
 * network-free double the issue requires. The full context boots with the offline {@code test} LLM
 * provider purely to satisfy the DD-005 startup gate; this test exercises retrieval, not the LLM.
 * Each test seeds a fresh {@code (workspace, project)} to stay isolated on the shared container.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class VectorRecallTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static final int DIM = PageEmbeddingStore.EMBEDDING_DIM;

    /** A fresh, isolated scope per test. */
    private Scope freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return Scope.of(ws, proj);
    }

    private UUID workspaceId(Scope s) {
        return jdbc().queryForObject(
                "SELECT id FROM workspaces WHERE slug = ?", UUID.class, s.workspaceSlug());
    }

    private UUID projectId(Scope s) {
        return jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?",
                UUID.class, s.workspaceSlug(), s.projectSlug());
    }

    /** Insert a latest page; returns its id. */
    private UUID seedPage(Scope s, String path, String title, String body) {
        UUID id = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, "
                        + "body, is_latest, access_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0)",
                id, workspaceId(s), projectId(s), s.workspaceSlug(), s.projectSlug(), path, title, body);
        return id;
    }

    /** A 1024-dim one-hot-ish unit vector with a 1 at {@code slot} (distinct, normalized). */
    private static float[] axis(int slot) {
        float[] v = new float[DIM];
        v[slot % DIM] = 1.0f;
        return v;
    }

    private RecallService recallWith(VectorArm arm) {
        return new HybridRecallService(new RecallRepository(jdbc()), new RrfFusion(), arm);
    }

    // --- 1. fusion improves recall -----------------------------------------------------------------

    @Test
    void vectorArmSurfacesASemanticOnlyHitThatFtsAndGraphMiss() {
        Scope s = freshScope();

        // An FTS-matching page anchors the query lexically.
        seedPage(s, "concepts/recall.md", "Hybrid recall",
                "reciprocal rank fusion blends full text and graph signals");

        // The semantic target shares NO query terms and is NOT linked, so neither the FTS arm nor the
        // graph arm can reach it. Only the vector arm can — its embedding is pinned next to the query.
        UUID semantic = seedPage(s, "concepts/semantic.md", "Embeddings overview",
                "dense vector similarity over compiled pages using cosine distance");

        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());
        // Pin the query and the semantic page to the SAME vector → it is the nearest neighbour.
        float[] shared = axis(7);
        ScriptedEmbedder embedder = ScriptedEmbedder.contractWidth()
                .map("reciprocal rank fusion", shared);
        PageEmbeddingService embed = new PageEmbeddingService(store, embedder);

        // Embed the semantic page (the write/backfill seam) with the same vector as the query.
        embedder.map(embedTextOf("Embeddings overview",
                "dense vector similarity over compiled pages using cosine distance"), shared);
        assertThat(embed.embedPage(new com.agentmemory.core.PageId(semantic),
                "Embeddings overview",
                "dense vector similarity over compiled pages using cosine distance")).isTrue();
        assertThat(store.countFor(new com.agentmemory.core.PageId(semantic))).isEqualTo(1);

        VectorArm arm = new VectorArm(store, embedder);
        assertThat(arm.enabled()).isTrue();

        // Baseline: FTS + graph only must NOT contain the semantic-only page.
        RecallResult ftsOnly = recallWith(null).search(RecallQuery.of("reciprocal rank fusion", s));
        assertThat(ftsOnly.hits()).extracting(RecallHit::path)
                .contains("concepts/recall.md")
                .doesNotContain("concepts/semantic.md");

        // With the vector arm fused in, the semantic page is now recalled.
        RecallResult fused = recallWith(arm).search(RecallQuery.of("reciprocal rank fusion", s));
        assertThat(fused.rawFallback()).isFalse();
        assertThat(fused.hits()).extracting(RecallHit::id)
                .as("semantic-only page pulled in via the vector arm")
                .contains(semantic.toString());
        // The lexical hit is still present (fusion adds the vector arm, it does not replace FTS).
        assertThat(fused.hits()).extracting(RecallHit::path).contains("concepts/recall.md");
    }

    // --- 2. graceful degradation (embedder down) ---------------------------------------------------

    @Test
    void recallStillReturnsFtsAndGraphWhenEmbedderIsUnavailable() {
        Scope s = freshScope();
        seedPage(s, "decisions/storage.md", "Storage decision",
                "we chose postgres with pgvector for the derived index");

        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());
        // Embedder throws on embed: the arm must swallow it and contribute nothing.
        VectorArm arm = new VectorArm(store, ScriptedEmbedder.unreachable());

        RecallResult r = recallWith(arm).search(RecallQuery.of("postgres pgvector", s));

        assertThat(r.rawFallback()).isFalse();
        assertThat(r.hits()).extracting(RecallHit::path).contains("decisions/storage.md");
        assertThat(r.hits().get(0).source()).isEqualTo(HitSource.PAGE);
    }

    @Test
    void recallStillReturnsFtsWhenNoEmbedderIsConfigured() {
        Scope s = freshScope();
        seedPage(s, "concepts/x.md", "Topic", "alpha beta gamma delta keyword");

        // No embedder at all (null) → arm disabled, but recall is unaffected.
        VectorArm arm = new VectorArm(new PageEmbeddingStore(jdbc()), null);
        assertThat(arm.enabled()).isFalse();
        assertThat(arm.rank(s.workspaceSlug(), s.projectSlug(), "keyword", 10).isEmpty()).isTrue();

        RecallResult r = recallWith(arm).search(RecallQuery.of("keyword", s));
        assertThat(r.hits()).extracting(RecallHit::path).contains("concepts/x.md");
    }

    // --- 3. dimension mismatch ---------------------------------------------------------------------

    @Test
    void wrongWidthEmbedderStoresNoVectorAndDisablesTheArm() {
        Scope s = freshScope();
        UUID page = seedPage(s, "concepts/dim.md", "Dimensions", "vector width contract page body");

        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());
        // 8-dim embedder vs the 1024-dim column: write must be skipped, not attempted-and-rejected.
        ScriptedEmbedder mismatched = ScriptedEmbedder.wrongWidth(8);
        PageEmbeddingService embed = new PageEmbeddingService(store, mismatched);

        assertThat(embed.embeddingsEnabled()).isFalse();
        assertThat(embed.embedPage(new com.agentmemory.core.PageId(page),
                "Dimensions", "vector width contract page body")).isFalse();
        assertThat(store.countFor(new com.agentmemory.core.PageId(page))).isZero();

        // The read arm also self-disables on a width mismatch and contributes nothing.
        VectorArm arm = new VectorArm(store, mismatched);
        assertThat(arm.enabled()).isFalse();

        // Recall still works via FTS.
        RecallResult r = recallWith(arm).search(RecallQuery.of("vector width contract", s));
        assertThat(r.hits()).extracting(RecallHit::path).contains("concepts/dim.md");
    }

    @Test
    void directUpsertOfAWrongWidthVectorIsRejectedLoudly() {
        Scope s = freshScope();
        UUID page = seedPage(s, "concepts/loud.md", "Loud", "body");
        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());

        // The store is the loud backstop: a wrong-width EmbeddingResult never reaches the column.
        var wrong = new com.agentmemory.llm.EmbeddingResult(new float[] {1f, 2f, 3f}, "voyage", "m", 3);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> store.upsert(new com.agentmemory.core.PageId(page), wrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
        assertThat(store.countFor(new com.agentmemory.core.PageId(page))).isZero();
    }

    @Test
    void directUpsertOfANonFiniteVectorIsRejectedBeforeTheDb() {
        Scope s = freshScope();
        UUID page = seedPage(s, "concepts/nan.md", "NaN", "body");
        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());

        // A degenerate (NaN) 1024-dim vector passes the width check but pgvector would reject it; the
        // encoder guard fails loudly with a clear message instead of an opaque CAST error, and stores
        // nothing.
        float[] degenerate = axis(3);
        degenerate[0] = Float.NaN;
        var result = new com.agentmemory.llm.EmbeddingResult(degenerate, "voyage", "voyage-3", DIM);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> store.upsert(new com.agentmemory.core.PageId(page), result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not finite");
        assertThat(store.countFor(new com.agentmemory.core.PageId(page))).isZero();
    }

    // --- store-level behaviour: re-embed, scope, nearest order -------------------------------------

    @Test
    void reEmbedSamePageProviderModelOverwritesInPlace() {
        Scope s = freshScope();
        UUID page = seedPage(s, "concepts/reembed.md", "Re-embed", "body");
        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());
        var pid = new com.agentmemory.core.PageId(page);

        store.upsert(pid, new com.agentmemory.llm.EmbeddingResult(axis(1), "voyage", "voyage-3", DIM));
        store.upsert(pid, new com.agentmemory.llm.EmbeddingResult(axis(2), "voyage", "voyage-3", DIM));

        // Still exactly one row (the unique (page, provider, model) constraint + ON CONFLICT update).
        assertThat(store.countFor(pid)).isEqualTo(1);
        // pages.embedding_id points at that row.
        UUID embeddingId = jdbc().queryForObject(
                "SELECT embedding_id FROM pages WHERE id = ?", UUID.class, page);
        assertThat(embeddingId).isNotNull();
    }

    @Test
    void nearestLatestRanksClosestVectorFirstAndScopesToProviderModel() {
        Scope s = freshScope();
        UUID near = seedPage(s, "p/near.md", "near", "body");
        UUID far = seedPage(s, "p/far.md", "far", "body");
        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());

        // near gets the query's exact vector; far gets an orthogonal one.
        store.upsert(new com.agentmemory.core.PageId(near),
                new com.agentmemory.llm.EmbeddingResult(axis(5), "voyage", "voyage-3", DIM));
        store.upsert(new com.agentmemory.core.PageId(far),
                new com.agentmemory.llm.EmbeddingResult(axis(900), "voyage", "voyage-3", DIM));
        // A vector under a DIFFERENT model must be invisible to a voyage-3 search.
        UUID other = seedPage(s, "p/other.md", "other", "body");
        store.upsert(new com.agentmemory.core.PageId(other),
                new com.agentmemory.llm.EmbeddingResult(axis(5), "voyage", "other-model", DIM));

        List<PageEmbeddingStore.VectorHit> hits = store.nearestLatest(
                s.workspaceSlug(), s.projectSlug(), axis(5), "voyage", "voyage-3", 10);

        assertThat(hits).extracting(PageEmbeddingStore.VectorHit::pageId)
                .containsExactly(near.toString(), far.toString()); // other-model excluded; near first
        assertThat(hits.get(0).distance()).isLessThan(hits.get(1).distance());
    }

    // --- helper ------------------------------------------------------------------------------------

    /** Mirror of PageEmbeddingService's title/body join, so a test can pin the page's embed text. */
    private static String embedTextOf(String title, String body) {
        return title.strip() + "\n\n" + body.strip();
    }
}
