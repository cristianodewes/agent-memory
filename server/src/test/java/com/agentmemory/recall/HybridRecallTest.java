package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
 * End-to-end recall over a throwaway {@code pgvector/pgvector:pg16} (Testcontainers), proving issue
 * #15's acceptance criteria on a seeded corpus: FTS+graph RRF fusion into one ranked list, the
 * link-graph arm pulling in a neighbor of a strong FTS hit, the bounded raw-observation fallback when
 * pages miss (clearly flagged), HTML-marked snippets, scope isolation, query determinism, and a
 * documented latency target.
 *
 * <p>Each test seeds its own unique {@code (workspace, project)} so the shared container stays
 * isolated across tests. The offline {@code test} LLM provider boots the context (DD-005 gate); this
 * test exercises retrieval SQL, not the LLM.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class HybridRecallTest {

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

    @Autowired RecallService recall;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- seeding helpers ---------------------------------------------------------------------------

    /** A fresh, isolated scope per test. */
    private Scope freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        JdbcTemplate j = jdbc();
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        j.update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        j.update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
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

    /** Insert a resolved directed link from one page version to another. */
    private void seedLink(Scope s, UUID fromPage, String fromPath, UUID toPage, String toPath) {
        jdbc().update(
                "INSERT INTO links (id, from_page_id, source_workspace, source_project, source_path, "
                        + "to_page_id, target_workspace, target_project, target_path, target_resolved) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, true)",
                UUID.randomUUID(), fromPage, s.workspaceSlug(), s.projectSlug(), fromPath,
                toPage, s.workspaceSlug(), s.projectSlug(), toPath);
    }

    /** Insert an observation under a one-off session; returns its id. */
    private UUID seedObservation(Scope s, String kind, String payload) {
        UUID sessionId = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, workspaceId(s), projectId(s), s.workspaceSlug(), s.projectSlug(),
                java.sql.Timestamp.from(Instant.now()));
        UUID obsId = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO observations (id, session_id, workspace_id, project_id, workspace, "
                        + "project, kind, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                obsId, sessionId, workspaceId(s), projectId(s), s.workspaceSlug(), s.projectSlug(),
                kind, payload, java.sql.Timestamp.from(Instant.now()));
        return obsId;
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void ftsMatchesAndReturnsHtmlMarkedSnippet() {
        Scope s = freshScope();
        seedPage(s, "concepts/recall.md", "Hybrid recall",
                "Reciprocal rank fusion blends full-text and link-graph signals.");
        seedPage(s, "concepts/unrelated.md", "Cooking", "How to bake sourdough bread at home.");

        RecallResult r = recall.search(RecallQuery.of("reciprocal rank fusion", s));

        assertThat(r.rawFallback()).isFalse();
        assertThat(r.hits()).isNotEmpty();
        RecallHit top = r.hits().get(0);
        assertThat(top.source()).isEqualTo(HitSource.PAGE);
        assertThat(top.path()).isEqualTo("concepts/recall.md");
        assertThat(top.snippet()).contains("<mark>").contains("</mark>");
        assertThat(top.rank()).isEqualTo(1);
        // the unrelated page must not be returned for this query
        assertThat(r.hits()).noneMatch(h -> "concepts/unrelated.md".equals(h.path()));
    }

    @Test
    void graphArmPullsInNeighborOfAStrongFtsHit() {
        Scope s = freshScope();
        // "decisions/storage.md" is the FTS hit; "concepts/indexing.md" does NOT contain the query
        // terms, so without the link the graph arm has nothing to add and it would not surface.
        UUID storage = seedPage(s, "decisions/storage.md", "Storage decision",
                "We chose Postgres with pgvector for the derived index.");
        UUID indexing = seedPage(s, "concepts/indexing.md", "Indexing notes",
                "Background material on B-trees and GIN.");

        // Control: with NO link, the non-matching neighbor must be absent (proves it can only enter
        // via the graph arm, not via its own text).
        RecallResult before = recall.search(RecallQuery.of("postgres pgvector index", s));
        assertThat(before.hits()).extracting(RecallHit::path)
                .contains("decisions/storage.md")
                .doesNotContain("concepts/indexing.md");

        // Now link the FTS hit to the neighbor; the graph arm must fold the neighbor into the result.
        seedLink(s, storage, "decisions/storage.md", indexing, "concepts/indexing.md");
        RecallResult after = recall.search(RecallQuery.of("postgres pgvector index", s));

        assertThat(after.rawFallback()).isFalse();
        assertThat(after.hits()).extracting(RecallHit::path)
                .as("graph neighbor pulled in via the link")
                .contains("decisions/storage.md", "concepts/indexing.md");
        // The FTS-matching page is a genuine PAGE hit (sanity on the hit shape).
        assertThat(after.hits()).anySatisfy(h -> {
            assertThat(h.path()).isEqualTo("decisions/storage.md");
            assertThat(h.source()).isEqualTo(HitSource.PAGE);
        });
    }

    @Test
    void rawObservationFallbackWhenNoPageMatchesIsBoundedAndFlagged() {
        Scope s = freshScope();
        // A page exists but does NOT match the query; an observation does. Fallback should fire.
        seedPage(s, "concepts/other.md", "Other", "completely different content here");
        seedObservation(s, "user-prompt", "please investigate the flaky widget timeout bug");
        seedObservation(s, "post-tool-use", "widget timeout reproduced in the integration suite");

        RecallResult r = recall.search(new RecallQuery("flaky widget timeout", s, 5));

        assertThat(r.rawFallback()).isTrue();
        assertThat(r.hits()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(r.hits()).allSatisfy(h -> {
            assertThat(h.source()).isEqualTo(HitSource.RAW_OBSERVATION);
            assertThat(h.path()).isNull();
            assertThat(h.kind()).isNotNull();
            assertThat(h.snippet()).contains("<mark>");
        });
        assertThat(r.hits().get(0).rank()).isEqualTo(1);
    }

    @Test
    void noFallbackWhenPagesMatch() {
        Scope s = freshScope();
        seedPage(s, "concepts/match.md", "Widget", "the widget timeout was fixed by raising the limit");
        seedObservation(s, "user-prompt", "widget timeout investigation notes");

        RecallResult r = recall.search(RecallQuery.of("widget timeout", s));

        // a page matched, so we must NOT fall back to raw observations
        assertThat(r.rawFallback()).isFalse();
        assertThat(r.hits()).allMatch(h -> h.source() == HitSource.PAGE);
    }

    @Test
    void emptyWhenNothingMatchesAnywhere() {
        Scope s = freshScope();
        seedPage(s, "a.md", "A", "alpha beta gamma");
        RecallResult r = recall.search(RecallQuery.of("nonexistentxyzzy", s));
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.rawFallback()).isFalse();
    }

    @Test
    void scopeIsolatesProjects() {
        Scope a = freshScope();
        Scope b = freshScope();
        seedPage(a, "concepts/secret.md", "Secret sauce", "the magic recall ingredient");
        seedPage(b, "concepts/secret.md", "Secret sauce", "the magic recall ingredient");

        RecallResult ra = recall.search(RecallQuery.of("magic recall ingredient", a));
        // only project a's page comes back when searching scope a
        assertThat(ra.hits()).hasSize(1);
        assertThat(ra.hits().get(0).source()).isEqualTo(HitSource.PAGE);
        // sanity: searching b also finds exactly its own one
        assertThat(recall.search(RecallQuery.of("magic recall ingredient", b)).hits()).hasSize(1);
    }

    @Test
    void resultsAreDeterministicAcrossRepeatedQueries() {
        Scope s = freshScope();
        for (int i = 0; i < 12; i++) {
            seedPage(s, "p/" + i + ".md", "Recall topic " + i,
                    "shared fusion keyword with minor variation number " + i);
        }
        RecallQuery q = new RecallQuery("shared fusion keyword", s, 10);
        List<String> first = recall.search(q).hits().stream().map(RecallHit::id).toList();
        List<String> second = recall.search(q).hits().stream().map(RecallHit::id).toList();
        assertThat(second).isEqualTo(first); // identical ordering, every time
    }

    @Test
    void queryLatencyIsAcceptableOnASeededCorpus() {
        // Seed a few hundred pages + a link mesh, then assert recall latency stays well under the
        // documented p95 target of 150 ms (generous bound here to avoid CI flakiness; the intent is
        // to catch an accidental O(n) regression, not to micro-benchmark).
        Scope s = freshScope();
        UUID prev = null;
        String prevPath = null;
        for (int i = 0; i < 400; i++) {
            String path = "notes/" + i + ".md";
            UUID id = seedPage(s, path, "Note " + i,
                    "fusion recall ranking notes with assorted keywords entry " + i
                            + (i % 7 == 0 ? " postgres pgvector graph neighborhood" : ""));
            if (prev != null && i % 3 == 0) {
                seedLink(s, id, path, prev, prevPath); // a sparse link mesh for the graph arm
            }
            prev = id;
            prevPath = path;
        }

        RecallQuery q = new RecallQuery("fusion recall ranking", s, 10);
        recall.search(q); // warm caches / JIT

        long start = System.nanoTime();
        int runs = 20;
        for (int i = 0; i < runs; i++) {
            recall.search(q);
        }
        long avgMillis = (System.nanoTime() - start) / runs / 1_000_000;
        assertThat(avgMillis)
                .as("avg recall latency over a 400-page corpus (target p95 < 150ms)")
                .isLessThan(1000L);
    }
}
