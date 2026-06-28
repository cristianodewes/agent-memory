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
 * DB-backed tests for the OR-combined FTS recall (issue #134) over a throwaway
 * {@code pgvector/pgvector:pg16} (Testcontainers). Before #134 the recall arms parsed the query with
 * {@code plainto_tsquery('english', …)}, which AND-combines every lexeme: a multi-term query matched
 * only pages containing <em>all</em> terms, so in a PT/EN-mixed store a query like
 * {@code "deploy produção"} matched zero pages and recall dropped to the (non-injected)
 * raw-observation fallback. The arms now OR-combine the parsed query, so <em>any</em> term matches
 * while {@code ts_rank} still ranks pages matching more terms higher.
 *
 * <p>These exercise the SQL arms directly ({@link RecallRepository} and a bare
 * {@link HybridRecallService} with no vector arm), keeping the assertions on the retrieval change
 * deterministic and decoupled from the downstream LLM rerank/gate — that is where precision is
 * handled ("retrieve broad, rerank narrow") and is out of scope here. Each test seeds its own unique
 * {@code (workspace, project)} so the shared container stays isolated.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class FtsOrCombineRecallTest {

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

    /**
     * FTS + graph only (no vector arm), built straight over the container so the OR-combine retrieval
     * change is asserted free of the autowired LLM-rerank decorator.
     */
    private RecallService recall() {
        return new HybridRecallService(new RecallRepository(jdbc()), new RrfFusion());
    }

    private RecallRepository repository() {
        return new RecallRepository(jdbc());
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void multiTermMixedQueryMatchesPagesSharingAnyTermNotRawFallback() {
        Scope s = freshScope();
        // No single page contains BOTH query terms: one EN page has only "deploy", one PT page has
        // only "produção". Under the old AND-combine neither matched "deploy produção", so recall fell
        // back to the observation below (which has both terms). OR-combine matches each page on its own
        // term — the literal production scenario from #130's Wave 1 validation.
        seedPage(s, "ops/deploy.md", "Edge deploy runbook",
                "how the edge server was deployed to the cluster and the rollout verified");
        seedPage(s, "ops/producao.md", "Métricas de produção",
                "as métricas de produção do ambiente foram coletadas após o ajuste final");
        // The regression trap: this observation has BOTH terms, so the pre-#134 AND-combine returned it
        // as a raw fallback (rawFallback=true). With the fix, pages match and the fallback never fires.
        seedObservation(s, "post-tool-use", "deploy de produção concluído com sucesso");

        RecallResult r = recall().search(new RecallQuery("deploy produção", s, 10));

        assertThat(r.rawFallback())
                .as("pages match either term, so recall does not fall back to raw observations")
                .isFalse();
        assertThat(r.hits()).extracting(RecallHit::path)
                .as("each page is pulled in by the single term it shares with the query")
                .contains("ops/deploy.md", "ops/producao.md");
        assertThat(r.hits()).allMatch(h -> h.source() == HitSource.PAGE);
    }

    @Test
    void tsRankOrdersThePageMatchingMoreTermsAboveTheOneMatchingFewer() {
        Scope s = freshScope();
        // Both pages match under OR-combine, but "both" matches all three query lexemes and "one" only
        // a single one — ts_rank(search_vector, q) must still rank the richer match first. The bodies
        // are the same length so ordering is driven purely by how many query lexemes each matches.
        UUID both = seedPage(s, "p/both.md", "Both page", "alpha beta gamma signals");
        UUID one = seedPage(s, "p/one.md", "One page", "alpha signals only here");

        // Assert at the FTS arm directly, isolating ts_rank ordering from the graph/fusion stages.
        List<Candidate> hits =
                repository().ftsPages(s.workspaceSlug(), s.projectSlug(), "alpha beta gamma", 10);

        assertThat(hits).extracting(c -> c.hit().path())
                .as("both pages match (any term), ranked richer-match first")
                .containsExactly("p/both.md", "p/one.md");
        assertThat(hits.get(0).key()).isEqualTo(both.toString());
        assertThat(hits.get(1).key()).isEqualTo(one.toString());
    }

    @Test
    void blankQueryIsANoOpThatMatchesNothingWithoutError() {
        Scope s = freshScope();
        seedPage(s, "p/x.md", "X", "alpha beta gamma");
        seedObservation(s, "user-prompt", "alpha beta gamma observation");
        RecallRepository repo = repository();

        // plainto_tsquery('english','') → empty tsquery → the '&'→'|' replace is a no-op → @@ matches
        // nothing, with no error, on both the page arm and the raw-observation arm. (RecallQuery itself
        // rejects blank text upstream; this guards the SQL fragment directly, as defense in depth.)
        assertThat(repo.ftsPages(s.workspaceSlug(), s.projectSlug(), "", 10)).isEmpty();
        assertThat(repo.ftsPages(s.workspaceSlug(), s.projectSlug(), "   ", 10)).isEmpty();
        assertThat(repo.rawObservations(s.workspaceSlug(), s.projectSlug(), "", 10)).isEmpty();
    }

    // --- seeding helpers (mirror HybridRecallTest) -------------------------------------------------

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
}
