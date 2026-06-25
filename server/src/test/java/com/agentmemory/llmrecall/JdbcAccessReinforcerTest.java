package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.Scope;
import java.sql.Timestamp;
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
 * End-to-end coverage of {@link JdbcAccessReinforcer} against a throwaway {@code pgvector/pgvector:pg16}
 * (Testcontainers): the additive bump increments {@code access_count} and stamps {@code last_accessed_at}
 * on exactly the returned PAGE page-version rows, is scoped to {@code (workspace, project)}, ignores
 * raw-observation and non-UUID hits, and never throws on an empty / unknown input. This is the write
 * side of the #24 decay-reinforcement seam (#21 acceptance: "reinforcement fires on returned hits").
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class JdbcAccessReinforcerTest {

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

    private JdbcAccessReinforcer reinforcer() {
        return new JdbcAccessReinforcer(jdbc());
    }

    private Scope freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate j = jdbc();
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        j.update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        j.update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
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
                id, wsId, projId, s.workspaceSlug(), s.projectSlug(), path, "Title", "body");
        return id;
    }

    private long accessCount(UUID pageId) {
        return jdbc().queryForObject("SELECT access_count FROM pages WHERE id = ?", Long.class, pageId);
    }

    private Timestamp lastAccessedAt(UUID pageId) {
        return jdbc().queryForObject(
                "SELECT last_accessed_at FROM pages WHERE id = ?", Timestamp.class, pageId);
    }

    private static RecallHit pageHit(UUID id) {
        return new RecallHit(
                HitSource.PAGE, id.toString(), "p.md", "T", null, 1.0, 1, "snip");
    }

    @Test
    void bumpsAccessCountAndStampsTimestampOnReturnedPages() {
        Scope s = freshScope();
        UUID a = seedPage(s, "a.md");
        UUID b = seedPage(s, "b.md");

        assertThat(accessCount(a)).isZero();
        assertThat(lastAccessedAt(a)).isNull();

        reinforcer().reinforce(s, List.of(pageHit(a), pageHit(b)));

        assertThat(accessCount(a)).isEqualTo(1);
        assertThat(accessCount(b)).isEqualTo(1);
        assertThat(lastAccessedAt(a)).isNotNull();
        assertThat(lastAccessedAt(b)).isNotNull();
    }

    @Test
    void isAdditiveAcrossRepeatedReinforcements() {
        Scope s = freshScope();
        UUID a = seedPage(s, "a.md");

        reinforcer().reinforce(s, List.of(pageHit(a)));
        reinforcer().reinforce(s, List.of(pageHit(a)));
        reinforcer().reinforce(s, List.of(pageHit(a)));

        assertThat(accessCount(a)).isEqualTo(3);
    }

    @Test
    void doesNotBumpPagesInAnotherScope() {
        Scope a = freshScope();
        Scope b = freshScope();
        UUID pageInA = seedPage(a, "shared.md");
        UUID pageInB = seedPage(b, "shared.md");

        // Reinforce using scope a but pass BOTH page ids; only the page actually in scope a is bumped.
        reinforcer().reinforce(a, List.of(pageHit(pageInA), pageHit(pageInB)));

        assertThat(accessCount(pageInA)).isEqualTo(1);
        assertThat(accessCount(pageInB)).isZero(); // protected by the workspace/project guard
    }

    @Test
    void ignoresRawObservationAndNonUuidHits() {
        Scope s = freshScope();
        UUID a = seedPage(s, "a.md");

        RecallHit raw = new RecallHit(
                HitSource.RAW_OBSERVATION, UUID.randomUUID().toString(), null, "obs", "k", 1.0, 1, "s");
        RecallHit notUuid = new RecallHit(HitSource.PAGE, "not-a-uuid", "x.md", "T", null, 1.0, 2, "s");

        // Must not throw on the non-UUID id, and must still bump the valid page.
        reinforcer().reinforce(s, List.of(pageHit(a), raw, notUuid));

        assertThat(accessCount(a)).isEqualTo(1);
    }

    @Test
    void emptyAndNullInputsAreNoOps() {
        Scope s = freshScope();
        UUID a = seedPage(s, "a.md");

        reinforcer().reinforce(s, List.of());
        reinforcer().reinforce(s, null);
        reinforcer().reinforce(null, List.of(pageHit(a)));

        assertThat(accessCount(a)).isZero(); // nothing happened
    }
}
