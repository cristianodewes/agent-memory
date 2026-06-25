package com.agentmemory.store;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Boots the full application context against a throwaway {@code pgvector/pgvector:pg16} Postgres and
 * proves the issue #4 schema: Flyway applies cleanly at startup, every ARCHITECTURE §4.2 table and
 * its 3-tuple identity columns exist, the {@code tsvector} FTS columns + GIN indexes exist on
 * {@code pages}/{@code observations}, and the {@code pgvector} column + {@code (provider, model,
 * dim)} exist on {@code page_embeddings} — with a real FTS query and a real vector distance query
 * exercising both indexes.
 *
 * <p>The container is wired via {@link DynamicPropertySource} rather than {@code @ServiceConnection}
 * so the same run can flip {@code spring.flyway.enabled} back on (it is off for the DB-less context
 * tests) and pin the pgvector image explicitly. That the Spring context starts at all is itself the
 * "app boot applies the migrations cleanly" acceptance check — {@code FlywayMigrationInitializer}
 * runs before the context is ready.
 *
 * <p>The LLM is a required dependency (DD-005): a full-context boot fails fast unless a chat
 * provider is reachable, so the deterministic, offline {@code test} provider is selected for both
 * axes here (same as {@link com.agentmemory.AgentMemoryServerApplicationTests}) — this test is about
 * the schema, not the LLM.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class SchemaMigrationTest {

    /**
     * The exact image issue #4 names. pg16 + pgvector so {@code CREATE EXTENSION vector} and the
     * HNSW index in V7 succeed without an external service (CI-friendly).
     */
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway is default-on (FlywayAutoConfiguration runs at context refresh); pointing the
        // DataSource at the container above is all that is needed to migrate the real schema.
    }

    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- Tables --------------------------------------------------------------------------------

    @Test
    void allArchitectureTablesExist() {
        List<String> tables = jdbc().queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);
        assertThat(tables).contains(
                "workspaces", "projects", "pages", "sessions", "observations",
                "links", "handoffs", "page_embeddings", "audit_log", "pending_writes");
    }

    @Test
    void extensionsAreInstalled() {
        List<String> extensions = jdbc().queryForList(
                "SELECT extname FROM pg_extension", String.class);
        assertThat(extensions).contains("vector", "pg_trgm");
    }

    // --- 3-tuple identity columns (invariant #4) -----------------------------------------------

    @Test
    void everyDomainTableCarriesTheIdentityTuple() {
        // workspace + project on every domain row; path on the page-scoped / mutation tables.
        for (String table : List.of(
                "pages", "sessions", "observations", "handoffs", "audit_log", "pending_writes")) {
            assertThat(columnsOf(table))
                    .as("%s identity columns", table)
                    .contains("workspace", "project");
        }
        assertThat(columnsOf("pages")).contains("path");
        // links carries the source/target identity coordinates instead of a single tuple.
        assertThat(columnsOf("links")).contains(
                "source_workspace", "source_project", "source_path",
                "target_workspace", "target_project", "target_path");
    }

    @Test
    void pagesModelTheVersionChain() {
        assertThat(columnsOf("pages")).contains(
                "is_latest", "supersedes", "access_count", "last_accessed_at", "embedding_id");
    }

    // --- tsvector FTS columns + GIN indexes ----------------------------------------------------

    @Test
    void pagesAndObservationsHaveTsvectorColumns() {
        assertThat(columnType("pages", "search_vector")).isEqualTo("tsvector");
        assertThat(columnType("observations", "search_vector")).isEqualTo("tsvector");
    }

    @Test
    void ftsGinIndexesExist() {
        assertThat(indexDef("pages_fts")).contains("USING gin").contains("search_vector");
        assertThat(indexDef("observations_fts")).contains("USING gin").contains("search_vector");
    }

    // --- pgvector column + (provider, model, dim) + HNSW ---------------------------------------

    @Test
    void pageEmbeddingsHasVectorColumnAndDenormalizedTriple() {
        // udt_name for a pgvector column is 'vector'.
        assertThat(columnType("page_embeddings", "embedding")).isEqualTo("vector");
        assertThat(columnsOf("page_embeddings")).contains("provider", "model", "dim");
    }

    @Test
    void pageEmbeddingsHasHnswVectorIndex() {
        assertThat(indexDef("page_embeddings_hnsw"))
                .contains("USING hnsw")
                .contains("vector_cosine_ops");
    }

    @Test
    void embeddingDimensionContractIs1024() {
        // The fixed pgvector width is the documented embedding-dim contract (#6 voyage-3 = 1024).
        Integer dim = jdbc().queryForObject(
                "SELECT atttypmod FROM pg_attribute "
                        + "WHERE attrelid = 'page_embeddings'::regclass AND attname = 'embedding'",
                Integer.class);
        assertThat(dim).isEqualTo(1024);
    }

    // --- Live FTS + vector queries (prove the indexes actually work) ---------------------------

    @Test
    void fullTextSearchMatchesAStoredPage() {
        UUID pageId = seedPageWithEmbeddings();
        Integer hits = jdbc().queryForObject(
                "SELECT count(*) FROM pages "
                        + "WHERE id = ? "
                        + "  AND search_vector @@ plainto_tsquery('english', 'reciprocal fusion')",
                Integer.class, pageId);
        assertThat(hits).isEqualTo(1);
    }

    @Test
    void vectorDistanceQueryRanksTheNearestEmbedding() {
        seedPageWithEmbeddings();
        // Nearest-neighbour by cosine distance to an all-ones probe; the closer of the two seeded
        // vectors must come first, proving the pgvector column + '<=>' operator work end-to-end.
        String nearestModel = jdbc().queryForObject(
                "SELECT model FROM page_embeddings "
                        + "ORDER BY embedding <=> CAST(? AS vector) LIMIT 1",
                String.class, onesLiteral(1024));
        assertThat(nearestModel).isEqualTo("near-model");
    }

    // --- Seed migration ------------------------------------------------------------------------

    @Test
    void devSeedInsertedTheDemoCoordinate() {
        Integer ws = jdbc().queryForObject(
                "SELECT count(*) FROM workspaces WHERE slug = 'demo'", Integer.class);
        Integer proj = jdbc().queryForObject(
                "SELECT count(*) FROM projects WHERE workspace = 'demo' AND slug = 'agent-memory'",
                Integer.class);
        assertThat(ws).isEqualTo(1);
        assertThat(proj).isEqualTo(1);
    }

    @Test
    void devSeedIsIdempotentOnReplay() {
        // Re-run the V9 seed statements: the ON CONFLICT DO NOTHING guards make a manual replay a
        // no-op (issue #4: the seed migration must be idempotent), leaving exactly one demo row.
        jdbc().update("INSERT INTO workspaces (id, slug) "
                + "VALUES ('00000000-0000-7000-8000-000000000001', 'demo') ON CONFLICT (id) DO NOTHING");
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) "
                + "VALUES ('00000000-0000-7000-8000-000000000002', "
                + "'00000000-0000-7000-8000-000000000001', 'demo', 'agent-memory') "
                + "ON CONFLICT (id) DO NOTHING");
        Integer ws = jdbc().queryForObject(
                "SELECT count(*) FROM workspaces WHERE slug = 'demo'", Integer.class);
        assertThat(ws).isEqualTo(1);
    }

    // --- helpers -------------------------------------------------------------------------------

    /**
     * Inserts one page under the seeded demo coordinate plus two embeddings: a "near" vector of all
     * ones (closest to the all-ones probe by cosine distance) and a "far" vector pointing the other
     * way. Returns the page id. The container (and its schema) is shared across the test methods, so
     * each call writes a UNIQUE page path — otherwise the {@code pages_latest_unique} partial index
     * (correctly) rejects a second {@code is_latest} page at the same (workspace, project, path).
     */
    private UUID seedPageWithEmbeddings() {
        UUID workspaceId = UUID.fromString("00000000-0000-7000-8000-000000000001");
        UUID projectId = UUID.fromString("00000000-0000-7000-8000-000000000002");
        UUID pageId = UUID.randomUUID();
        String path = "concepts/recall-" + pageId + ".md";
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, body) "
                        + "VALUES (?, ?, ?, 'demo', 'agent-memory', ?, ?, ?)",
                pageId, workspaceId, projectId, path,
                "Hybrid recall",
                "Reciprocal rank fusion blends FTS, graph and vector candidates.");

        jdbc().update(
                "INSERT INTO page_embeddings (id, page_id, provider, model, dim, embedding) "
                        + "VALUES (?, ?, 'voyage', 'near-model', 1024, CAST(? AS vector))",
                UUID.randomUUID(), pageId, onesLiteral(1024));
        jdbc().update(
                "INSERT INTO page_embeddings (id, page_id, provider, model, dim, embedding) "
                        + "VALUES (?, ?, 'voyage', 'far-model', 1024, CAST(? AS vector))",
                UUID.randomUUID(), pageId, signedLiteral(1024));
        return pageId;
    }

    /** A pgvector literal {@code [1,1,...,1]} of the given width. */
    private static String onesLiteral(int dim) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('1');
        }
        return sb.append(']').toString();
    }

    /** A pgvector literal that alternates {@code +1,-1,...} — far from the all-ones probe. */
    private static String signedLiteral(int dim) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i % 2 == 0 ? "1" : "-1");
        }
        return sb.append(']').toString();
    }

    private List<String> columnsOf(String table) {
        return jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                String.class, table);
    }

    private String columnType(String table, String column) {
        return jdbc().queryForObject(
                "SELECT udt_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    private String indexDef(String indexName) {
        return jdbc().queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, indexName);
    }
}
