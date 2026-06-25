package com.agentmemory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Exercises {@link PageRepository} end-to-end against a throwaway {@code pgvector/pgvector:pg16}
 * Postgres (Testcontainers), proving issue #12's acceptance criteria: the {@code is_latest} +
 * {@code supersedes} version chain, that latest reads never see superseded rows, that the generated
 * FTS column is populated in the same transaction as the row, and that concurrent supersede of the
 * same path is serialized with no lost updates. The offline {@code test} LLM provider is selected so
 * the full context boots (DD-005 fail-fast gate); this test is about storage, not the LLM.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class PageStorageTest {

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

    @Autowired PageRepository pages;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static Identity pageAt(String path) {
        // Unique workspace per call keeps tests isolated on the shared container.
        return Identity.ofPage(
                WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", "")),
                ProjectId.of("proj"),
                PagePath.of(path));
    }

    // --- version chain -------------------------------------------------------------------------

    @Test
    void firstVersionHasNoSupersedesAndIsLatest() {
        Identity id = pageAt("concepts/recall.md");
        PageRecord v1 = pages.create(id, "Recall", "first body");

        assertThat(v1.isLatest()).isTrue();
        assertThat(v1.page().supersedes()).isNull();
        assertThat(v1.accessCount()).isZero();
        assertThat(v1.lastAccessedAt()).isNull();
        assertThat(v1.identity()).isEqualTo(id);
    }

    @Test
    void newVersionSupersedesPriorAtomically() {
        Identity id = pageAt("concepts/recall.md");
        PageRecord v1 = pages.create(id, "Recall", "first body");
        PageRecord v2 = pages.create(id, "Recall v2", "second body");

        // New version is latest and links back to v1.
        assertThat(v2.isLatest()).isTrue();
        assertThat(v2.page().supersedes()).isEqualTo(v1.id());
        assertThat(v2.id()).isNotEqualTo(v1.id());

        // Prior version is preserved but no longer latest (never hard-deleted).
        PageRecord reloadedV1 = pages.findById(v1.id()).orElseThrow();
        assertThat(reloadedV1.isLatest()).isFalse();
        assertThat(reloadedV1.page().body()).isEqualTo("first body");

        // Exactly one latest row for the path at the DB level.
        Integer latestCount = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ? AND is_latest",
                Integer.class,
                id.workspace().value(), id.project().value(), id.page().value());
        assertThat(latestCount).isEqualTo(1);
    }

    @Test
    void chainOfThreeVersionsLinksBackInOrder() {
        Identity id = pageAt("decisions/storage.md");
        PageRecord v1 = pages.create(id, "t", "b1");
        PageRecord v2 = pages.create(id, "t", "b2");
        PageRecord v3 = pages.create(id, "t", "b3");

        assertThat(v3.page().supersedes()).isEqualTo(v2.id());
        assertThat(v2.page().supersedes()).isEqualTo(v1.id());
        assertThat(v1.page().supersedes()).isNull();
        assertThat(pages.readLatest(id).orElseThrow().id()).isEqualTo(v3.id());

        // All three versions are retained.
        Integer total = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ?",
                Integer.class,
                id.workspace().value(), id.project().value(), id.page().value());
        assertThat(total).isEqualTo(3);
    }

    // --- latest reads never see superseded rows ------------------------------------------------

    @Test
    void readLatestReturnsCurrentVersionOnly() {
        Identity id = pageAt("concepts/x.md");
        pages.create(id, "t", "old");
        PageRecord v2 = pages.create(id, "t", "new");

        Optional<PageRecord> latest = pages.readLatest(id);
        assertThat(latest).isPresent();
        assertThat(latest.get().id()).isEqualTo(v2.id());
        assertThat(latest.get().page().body()).isEqualTo("new");
    }

    @Test
    void readLatestIsEmptyForUnknownPath() {
        assertThat(pages.readLatest(pageAt("nope/missing.md"))).isEmpty();
    }

    @Test
    void listLatestExcludesSupersededAndScopesToProject() {
        WorkspaceId ws = WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
        ProjectId proj = ProjectId.of("proj");
        Identity a = Identity.ofPage(ws, proj, PagePath.of("a.md"));
        Identity b = Identity.ofPage(ws, proj, PagePath.of("b.md"));

        pages.create(a, "t", "a-old");
        PageRecord aLatest = pages.create(a, "t", "a-new"); // supersede a
        PageRecord bOnly = pages.create(b, "t", "b");

        List<PageRecord> latest = pages.listLatest(ws, proj);
        assertThat(latest).extracting(PageRecord::id)
                .containsExactlyInAnyOrder(aLatest.id(), bOnly.id());
        assertThat(latest).allMatch(PageRecord::isLatest);
    }

    // --- FTS populated in the same transaction -------------------------------------------------

    @Test
    void ftsColumnIsPopulatedInTheSameWriteNoPostCommitIndexing() {
        Identity id = pageAt("concepts/fusion.md");
        PageRecord v1 = pages.create(
                id, "Hybrid recall", "Reciprocal rank fusion blends FTS, graph and vector.");

        // Immediately queryable by FTS — the generated search_vector was computed by the INSERT,
        // not by any later indexing pass.
        Integer hitsTitle = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE id = ? "
                        + "AND search_vector @@ plainto_tsquery('english', 'hybrid recall')",
                Integer.class, v1.id().value());
        Integer hitsBody = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE id = ? "
                        + "AND search_vector @@ plainto_tsquery('english', 'reciprocal fusion')",
                Integer.class, v1.id().value());
        assertThat(hitsTitle).isEqualTo(1);
        assertThat(hitsBody).isEqualTo(1);

        // After supersede, the new body is searchable and the only latest hit for the path.
        PageRecord v2 = pages.create(id, "Hybrid recall", "Graph neighbourhood expansion details.");
        Integer latestHit = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ? "
                        + "AND is_latest AND search_vector @@ plainto_tsquery('english', 'neighbourhood')",
                Integer.class,
                id.workspace().value(), id.project().value(), id.page().value());
        assertThat(latestHit).isEqualTo(1);
        assertThat(v2.id()).isNotEqualTo(v1.id());
    }

    // --- #13 extension hook ---------------------------------------------------------------------

    @Test
    void writeCallbackRunsInTransactionWithPersistedRow() {
        Identity id = pageAt("concepts/hooked.md");
        var seen = new java.util.concurrent.atomic.AtomicReference<PageRecord>();
        PageRecord created = pages.create(id, "t", "b", persisted -> {
            // Visible inside the same transaction: the row is queryable here.
            Integer rows = jdbc().queryForObject(
                    "SELECT count(*) FROM pages WHERE id = ?", Integer.class,
                    persisted.id().value());
            assertThat(rows).isEqualTo(1);
            seen.set(persisted);
        });
        assertThat(seen.get().id()).isEqualTo(created.id());
    }

    @Test
    void throwingCallbackRollsBackTheWholeWrite() {
        Identity id = pageAt("concepts/rollback.md");
        assertThatThrownBy(() -> pages.create(id, "t", "b", persisted -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(PageWriteException.class);

        // Row was rolled back: nothing persisted at that path.
        assertThat(pages.readLatest(id)).isEmpty();
        Integer rows = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ?",
                Integer.class,
                id.workspace().value(), id.project().value(), id.page().value());
        assertThat(rows).isZero();
    }

    // --- concurrency: serialized supersede, no lost updates ------------------------------------

    @Test
    void concurrentCreatesOnSamePathAreSerializedWithNoLostUpdates() throws Exception {
        Identity id = pageAt("concepts/contended.md");
        int writers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            var startGate = new java.util.concurrent.CountDownLatch(1);
            List<Callable<PageId>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final int n = i;
                tasks.add(() -> {
                    startGate.await();
                    return pages.create(id, "t", "body-" + n).id();
                });
            }
            List<Future<PageId>> futures = new java.util.ArrayList<>();
            for (Callable<PageId> t : tasks) {
                futures.add(pool.submit(t));
            }
            startGate.countDown();
            for (Future<PageId> f : futures) {
                f.get(60, TimeUnit.SECONDS); // each must succeed (no constraint-violation failures)
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        String ws = id.workspace().value();
        String proj = id.project().value();
        String path = id.page().value();

        // Exactly one latest survives.
        Integer latest = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ? AND is_latest",
                Integer.class, ws, proj, path);
        assertThat(latest).isEqualTo(1);

        // No lost updates: every create produced a distinct retained version, fully chained.
        Integer total = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ?",
                Integer.class, ws, proj, path);
        assertThat(total).isEqualTo(writers);

        // The chain is well-formed: exactly one row has a NULL supersedes (the first), and every
        // non-null supersedes points at a distinct prior row in this path (no two share a parent).
        Integer roots = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ? "
                        + "AND supersedes IS NULL",
                Integer.class, ws, proj, path);
        assertThat(roots).isEqualTo(1);
        Integer distinctParents = jdbc().queryForObject(
                "SELECT count(DISTINCT supersedes) FROM pages "
                        + "WHERE workspace = ? AND project = ? AND path = ? AND supersedes IS NOT NULL",
                Integer.class, ws, proj, path);
        assertThat(distinctParents).isEqualTo(writers - 1);
    }
}
