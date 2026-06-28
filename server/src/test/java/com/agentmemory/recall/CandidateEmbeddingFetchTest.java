package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.PageId;
import com.agentmemory.llm.EmbeddingResult;
import com.agentmemory.store.PageEmbeddingStore;
import java.util.List;
import java.util.Map;
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
 * Testcontainers coverage of the candidate-embedding read added for the recall MMR diversity pass
 * (issue #141): {@link PageEmbeddingStore#fetchByPageIds} round-trips stored {@code vector(1024)}
 * embeddings back to {@code float[]} by id, scoped to one {@code (provider, model)}, and
 * {@link PageEmbeddingService#embeddingsFor} layers the active-embedder gate (DD-005) on top. Runs
 * against a throwaway {@code pgvector/pgvector:pg16}, seeding a fresh scope per test.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class CandidateEmbeddingFetchTest {

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

    private static final int DIM = PageEmbeddingStore.EMBEDDING_DIM;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** A 1024-dim unit vector with a 1 at {@code slot} — exactly representable, so it round-trips. */
    private static float[] axis(int slot) {
        float[] v = new float[DIM];
        v[slot % DIM] = 1.0f;
        return v;
    }

    private Scope freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, "proj");
        return Scope.of(ws, "proj");
    }

    private UUID seedPage(Scope s, String path) {
        UUID wsId = jdbc().queryForObject(
                "SELECT id FROM workspaces WHERE slug = ?", UUID.class, s.workspaceSlug());
        UUID projId = jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?",
                UUID.class, s.workspaceSlug(), s.projectSlug());
        UUID id = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, "
                        + "body, is_latest, access_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0)",
                id, wsId, projId, s.workspaceSlug(), s.projectSlug(), path, "T", "body");
        return id;
    }

    @Test
    void fetchByPageIdsRoundTripsVectorsScopedToProviderModelAndOmitsTheRest() {
        Scope s = freshScope();
        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());

        UUID p1 = seedPage(s, "p/1.md");
        UUID p2 = seedPage(s, "p/2.md");
        UUID p3 = seedPage(s, "p/3.md"); // seeded but never embedded

        store.upsert(new PageId(p1), new EmbeddingResult(axis(5), "voyage", "voyage-3", DIM));
        store.upsert(new PageId(p2), new EmbeddingResult(axis(900), "voyage", "voyage-3", DIM));
        // A vector under a DIFFERENT model must NOT be returned by a voyage-3 fetch.
        store.upsert(new PageId(p3), new EmbeddingResult(axis(7), "voyage", "other-model", DIM));

        Map<String, float[]> got = store.fetchByPageIds(
                List.of(p1.toString(), p2.toString(), p3.toString(), UUID.randomUUID().toString()),
                "voyage", "voyage-3");

        assertThat(got).containsOnlyKeys(p1.toString(), p2.toString());
        assertThat(got.get(p1.toString())).containsExactly(axis(5)); // exact round-trip of the stored vector
        assertThat(got.get(p2.toString())).containsExactly(axis(900));
    }

    @Test
    void fetchByPageIdsIsEmptyForEmptyInputOrNoMatches() {
        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());
        assertThat(store.fetchByPageIds(List.of(), "voyage", "voyage-3")).isEmpty();
        assertThat(store.fetchByPageIds(List.of(UUID.randomUUID().toString()), "voyage", "voyage-3"))
                .isEmpty();
        // A non-UUID id is skipped defensively rather than failing the query.
        assertThat(store.fetchByPageIds(List.of("not-a-uuid"), "voyage", "voyage-3")).isEmpty();
    }

    @Test
    void embeddingsForReadsUnderTheActiveEmbedderAndDegradesWhenDisabled() {
        Scope s = freshScope();
        PageEmbeddingStore store = new PageEmbeddingStore(jdbc());
        UUID p1 = seedPage(s, "p/a.md");
        UUID p2 = seedPage(s, "p/b.md");
        store.upsert(new PageId(p1), new EmbeddingResult(axis(3), "voyage", "voyage-3", DIM));
        store.upsert(new PageId(p2), new EmbeddingResult(axis(4), "voyage", "voyage-3", DIM));
        List<String> ids = List.of(p1.toString(), p2.toString());

        // Active embedder (voyage/voyage-3, contract width): reads both candidate vectors.
        PageEmbeddingService enabled = new PageEmbeddingService(store, ScriptedEmbedder.contractWidth());
        assertThat(enabled.embeddingsFor(ids)).containsOnlyKeys(p1.toString(), p2.toString());

        // No embedder configured → diversity input unavailable, empty map (DD-005), never throws.
        assertThat(new PageEmbeddingService(store, null).embeddingsFor(ids)).isEmpty();

        // Width-mismatched embedder → embeddingsEnabled() is false → empty map.
        PageEmbeddingService mismatched =
                new PageEmbeddingService(store, ScriptedEmbedder.wrongWidth(8));
        assertThat(mismatched.embeddingsFor(ids)).isEmpty();
    }
}
