package com.agentmemory.curate;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Rule tests for the zero-cost curator (issue #29) against a throwaway {@code pgvector/pgvector:pg16}
 * Postgres (Testcontainers): one assertion block per rule — cold episodic pages, stale slots,
 * duplicate normalized titles, and dangling cross-project links — plus the negative cases that must
 * NOT fire. Timestamps/layers are seeded with raw SQL so each rule's trigger is exact; the dangling
 * rule is driven through the real {@link PageRepository#create} + {@link WikiLinkService} link path.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class CuratorServiceTest {

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

    @Autowired CuratorService curator;
    @Autowired PageRepository pages;
    @Autowired WikiLinkService links;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** Ensure a workspace + project row exists, returning the project id. */
    private UUID ensureProject(String ws, String proj) {
        UUID wsId = jdbc().query("SELECT id FROM workspaces WHERE slug = ?",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null, ws);
        if (wsId == null) {
            wsId = UUID.randomUUID();
            jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        }
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return projId;
    }

    /** Raw-insert a latest page with explicit layer and age, for exact rule triggering. */
    private void seedPage(String ws, String proj, UUID projId, String path, String title,
            String layer, Instant created, Instant updated, Instant lastAccessed) {
        UUID wsId = jdbc().queryForObject("SELECT id FROM workspaces WHERE slug = ?", UUID.class, ws);
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, body, "
                        + "is_latest, access_count, layer, created_at, updated_at, last_accessed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0, ?, ?, ?, ?)",
                UUID.randomUUID(), wsId, projId, ws, proj, path, title, "body", layer,
                ts(created), ts(updated), lastAccessed == null ? null : ts(lastAccessed));
    }

    private static java.sql.Timestamp ts(Instant i) {
        return java.sql.Timestamp.from(i);
    }

    private static Instant daysAgo(int d) {
        return Instant.now().minus(d, ChronoUnit.DAYS);
    }

    // --- cold episodic -----------------------------------------------------------------------------

    @Test
    void coldEpisodicPageIsFlaggedButFreshAndNonEpisodicAreNot() {
        WorkspaceId ws = freshWorkspace();
        String w = ws.value();
        UUID projId = ensureProject(w, "app");
        // cold: episodic, created 40d ago, never accessed -> flagged
        seedPage(w, "app", projId, "sessions/old.md", "Old", "episodic",
                daysAgo(40), daysAgo(40), null);
        // fresh episodic -> not flagged
        seedPage(w, "app", projId, "sessions/new.md", "New", "episodic",
                daysAgo(1), daysAgo(1), null);
        // old but semantic (timeless) -> not flagged by the episodic rule
        seedPage(w, "app", projId, "concepts/core.md", "Core", "semantic",
                daysAgo(99), daysAgo(99), null);

        CuratorReport r = curator.curate(Scope.of(w, "app"));
        assertThat(r.findings())
                .filteredOn(f -> f.rule() == CuratorRule.COLD_EPISODIC)
                .extracting(CuratorFinding::path)
                .containsExactly("sessions/old.md");
    }

    // --- stale slot --------------------------------------------------------------------------------

    @Test
    void staleSlotIsFlaggedButFreshSlotIsNot() {
        WorkspaceId ws = freshWorkspace();
        String w = ws.value();
        UUID projId = ensureProject(w, "app");
        seedPage(w, "app", projId, "_slots/identity.md", "Identity", "semantic",
                daysAgo(120), daysAgo(120), null); // stale: not updated in 120d
        seedPage(w, "app", projId, "_slots/focus.md", "Focus", "semantic",
                daysAgo(120), daysAgo(2), null);    // updated recently -> fresh

        CuratorReport r = curator.curate(Scope.of(w, "app"));
        assertThat(r.findings())
                .filteredOn(f -> f.rule() == CuratorRule.STALE_SLOT)
                .extracting(CuratorFinding::path)
                .containsExactly("_slots/identity.md");
    }

    // --- duplicate titles --------------------------------------------------------------------------

    @Test
    void duplicateNormalizedTitlesAreFlagged() {
        WorkspaceId ws = freshWorkspace();
        String w = ws.value();
        UUID projId = ensureProject(w, "app");
        // same title modulo case/whitespace -> a duplicate group
        seedPage(w, "app", projId, "concepts/a.md", "Recall Design", "semantic",
                daysAgo(1), daysAgo(1), null);
        seedPage(w, "app", projId, "concepts/b.md", "  recall design ", "semantic",
                daysAgo(1), daysAgo(1), null);
        // a distinct title -> not part of any duplicate group
        seedPage(w, "app", projId, "concepts/c.md", "Something else", "semantic",
                daysAgo(1), daysAgo(1), null);

        CuratorReport r = curator.curate(Scope.of(w, "app"));
        assertThat(r.findings())
                .filteredOn(f -> f.rule() == CuratorRule.DUPLICATE_TITLE)
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.path()).isEqualTo("concepts/a.md"); // first colliding path
                    assertThat(f.detail()).contains("concepts/a.md", "concepts/b.md");
                    assertThat(f.detail()).doesNotContain("concepts/c.md");
                });
    }

    // --- dangling cross-project link (via the real link path + #28 lint) ---------------------------

    @Test
    void staleDanglingCrossProjectLinkIsFlaggedNotSameProjectNorRecent() {
        WorkspaceId ws = freshWorkspace();
        String w = ws.value();
        // A page in "app" with a cross-project link to a non-existent platform page, and a same-project
        // dangling link. Both are written through the real writer + link path.
        PageRecord src = pages.create(
                Identity.ofPage(ws, ProjectId.of("app"), PagePath.of("decisions/use.md")),
                "Use platform",
                "depends on [[platform:concepts/auth]] and on [[concepts/missing]] (same project)");
        links.syncPageLinks(src);

        // Age every unresolved link from this page past the 7d dangling cutoff so they classify dangling.
        jdbc().update(
                "UPDATE links SET created_at = now() - interval '30 days' "
                        + "WHERE source_workspace = ? AND source_path = ? AND NOT target_resolved",
                w, "decisions/use.md");

        CuratorReport r = curator.curate(Scope.of(w, "app"));
        // Only the cross-project dangle is flagged; the same-project [[concepts/missing]] is not.
        assertThat(r.findings())
                .filteredOn(f -> f.rule() == CuratorRule.DANGLING_CROSS_PROJECT)
                .singleElement()
                .satisfies(f -> {
                    assertThat(f.path()).isEqualTo("decisions/use.md");
                    assertThat(f.detail()).contains("platform", "concepts/auth.md");
                });
    }

    @Test
    void cleanProjectProducesNoFindings() {
        WorkspaceId ws = freshWorkspace();
        String w = ws.value();
        UUID projId = ensureProject(w, "app");
        seedPage(w, "app", projId, "concepts/fresh.md", "Fresh", "semantic",
                daysAgo(1), daysAgo(1), null);

        CuratorReport r = curator.curate(Scope.of(w, "app"));
        assertThat(r.isClean()).isTrue();
        assertThat(r.total()).isZero();
    }
}
