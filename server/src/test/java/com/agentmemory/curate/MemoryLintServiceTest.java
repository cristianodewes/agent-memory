package com.agentmemory.curate;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
 * Integration tests for {@code memory_lint} (issue #29) against a throwaway {@code pgvector/pgvector:pg16}
 * Postgres (Testcontainers). They drive the rule path (the default test LLM makes the contradiction
 * pass a no-op, so these assert the deterministic rule findings) and prove the two regimes: a
 * {@code dry_run} preview writes nothing, and a staged run persists a {@code _lint/} page that links to
 * the flagged pages.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class MemoryLintServiceTest {

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

    @Autowired MemoryLintService lint;
    @Autowired PageRepository pages;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** Seed a workspace + project with two duplicate-title pages so a rule reliably fires. */
    private String seedProjectWithDuplicateTitles(WorkspaceId ws) {
        String w = ws.value();
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, w);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, w, "app");
        seedPage(wsId, projId, w, "concepts/a.md", "Recall design");
        seedPage(wsId, projId, w, "concepts/b.md", "recall design"); // same title, different case
        return w;
    }

    private void seedPage(UUID wsId, UUID projId, String ws, String path, String title) {
        Instant now = Instant.now().minus(1, ChronoUnit.DAYS);
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, body, "
                        + "is_latest, access_count, layer, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0, 'semantic', ?, ?)",
                UUID.randomUUID(), wsId, projId, ws, "app", path, title, "body",
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    }

    @Test
    void dryRunReturnsRuleFindingsWithoutWriting() {
        WorkspaceId ws = freshWorkspace();
        String w = seedProjectWithDuplicateTitles(ws);

        LintReport report = lint.lint(Scope.of(w, "app"), /*contradictions*/ false, /*dryRun*/ true);

        assertThat(report.written()).isFalse();
        assertThat(report.lintPath()).isNull();
        assertThat(report.ruleFindings())
                .anySatisfy(f -> assertThat(f.rule()).isEqualTo(CuratorRule.DUPLICATE_TITLE));
        // Nothing persisted under _lint/.
        Optional<PageRecord> lintPage = pages.readLatest(
                Identity.ofPage(ws, ProjectId.of("app"), PagePath.of(MemoryLintService.LINT_PATH)));
        assertThat(lintPage).isEmpty();
    }

    @Test
    void stagingPersistsALintPageLinkingTheFlaggedPages() {
        WorkspaceId ws = freshWorkspace();
        String w = seedProjectWithDuplicateTitles(ws);

        LintReport report = lint.lint(Scope.of(w, "app"), /*contradictions*/ false, /*dryRun*/ false);

        assertThat(report.written()).isTrue();
        assertThat(report.lintPath()).isEqualTo(MemoryLintService.LINT_PATH);

        Optional<PageRecord> lintPage = pages.readLatest(
                Identity.ofPage(ws, ProjectId.of("app"), PagePath.of(MemoryLintService.LINT_PATH)));
        assertThat(lintPage).isPresent();
        String body = lintPage.get().page().body();
        assertThat(body).contains("Lint report", "DUPLICATE_TITLE");
        // The report links the flagged page with a wikilink (so it is a real graph node).
        assertThat(body).contains("[[concepts/a]]");
    }
}
