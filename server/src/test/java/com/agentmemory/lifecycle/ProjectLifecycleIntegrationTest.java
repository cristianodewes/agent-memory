package com.agentmemory.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiPaths;
import com.agentmemory.wiki.WikiWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Integration tests for issue #33's rename / move / purge acceptance criteria against a Testcontainers
 * {@code pgvector/pgvector:pg16} Postgres:
 *
 * <ul>
 *   <li><b>Rename</b> updates identity across the index, moves the wiki subtree, re-points
 *       cross-project links, and leaves sibling projects untouched.</li>
 *   <li><b>Move</b> relocates a project to another workspace (FK + denormalized slugs), with the wiki
 *       subtree following.</li>
 *   <li><b>Purge</b> removes the project's wiki subtree, DB rows and embeddings idempotently; siblings
 *       survive.</li>
 *   <li>Every op writes an {@code audit_log} row with before/after identity.</li>
 * </ul>
 *
 * <p>The watcher and process lock are off (the latter via the test {@code application.properties}); the
 * data dir is a JUnit temp dir so the wiki gets its own git repo.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class ProjectLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    @Autowired PageRepository pages;
    @Autowired WikiWriter wikiWriter;
    @Autowired WikiPaths wikiPaths;
    @Autowired ProjectLifecycleService lifecycle;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Identity pageAt(WorkspaceId ws, String project, String path) {
        return Identity.ofPage(ws, ProjectId.of(project), PagePath.of(path));
    }

    /** Seed a page: DB row + atomic markdown file + git commit (the #13 path). */
    private PageRecord seed(Identity id, String title, String body) {
        return pages.create(id, title, body, wikiWriter.callbackFor("seed: " + id.page().value()));
    }

    private long pageCount(WorkspaceId ws, String project) {
        Long n = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ?",
                Long.class, ws.value(), project);
        return n == null ? 0 : n;
    }

    private boolean projectRowExists(WorkspaceId ws, String project) {
        Integer n = jdbc().queryForObject(
                "SELECT count(*) FROM projects WHERE workspace = ? AND slug = ?",
                Integer.class, ws.value(), project);
        return n != null && n > 0;
    }

    // --- rename ---------------------------------------------------------------------------------

    @Test
    void renameUpdatesIdentityMovesWikiRepointsLinksAndLeavesSiblingsUntouched() {
        WorkspaceId ws = freshWorkspace();
        Identity a = pageAt(ws, "alpha", "concepts/x.md");
        Identity sibling = pageAt(ws, "beta", "concepts/y.md");
        // A page in a THIRD project that links INTO alpha (cross-project), to prove target re-point.
        Identity linker = pageAt(ws, "gamma", "concepts/z.md");

        seed(a, "X", "alpha page\n");
        seed(sibling, "Y", "beta page\n");
        seed(linker, "Z", "see [[" + ws.value() + ":alpha:concepts/x.md]]\n");

        // Build the link graph (reindex extracts links from bodies).
        // We insert the cross-project link directly to avoid depending on reindex here: link from
        // gamma/z -> alpha/x.
        UUID linkerId = pages.readLatest(linker).orElseThrow().id().value();
        jdbc().update(
                "INSERT INTO links (id, from_page_id, source_workspace, source_project, source_path, "
                        + " to_page_id, target_workspace, target_project, target_path, target_resolved) "
                        + "SELECT ?, ?, ?, 'gamma', 'concepts/z.md', p.id, ?, 'alpha', 'concepts/x.md', true "
                        + "FROM pages p WHERE p.workspace = ? AND p.project = 'alpha' "
                        + "  AND p.path = 'concepts/x.md' AND p.is_latest",
                com.agentmemory.core.Uuid7.randomUuid(), linkerId, ws.value(), ws.value(), ws.value());

        Path alphaDir = wikiPaths.projectDir(a);
        assertThat(Files.isDirectory(alphaDir)).isTrue();

        ProjectOpResult result = lifecycle.renameProject(
                Identity.ofProject(ws, ProjectId.of("alpha")), ProjectId.of("renamed"));

        assertThat(result.op()).isEqualTo("rename");
        assertThat(result.after().project().value()).isEqualTo("renamed");
        assertThat(result.pagesAffected()).isEqualTo(1);

        // Identity moved in the index.
        assertThat(pageCount(ws, "alpha")).isZero();
        assertThat(pageCount(ws, "renamed")).isEqualTo(1);
        assertThat(projectRowExists(ws, "alpha")).isFalse();
        assertThat(projectRowExists(ws, "renamed")).isTrue();

        // The page is reachable at its new identity.
        assertThat(pages.readLatest(pageAt(ws, "renamed", "concepts/x.md"))).isPresent();

        // Wiki subtree moved on disk.
        assertThat(Files.isDirectory(alphaDir)).isFalse();
        assertThat(Files.isDirectory(wikiPaths.projectDir(pageAt(ws, "renamed", "concepts/x.md"))))
                .isTrue();

        // Cross-project link re-pointed: gamma/z now targets (ws, renamed, concepts/x.md).
        List<String> targets = jdbc().queryForList(
                "SELECT target_project FROM links "
                        + "WHERE source_workspace = ? AND source_project = 'gamma'",
                String.class, ws.value());
        assertThat(targets).containsExactly("renamed");

        // Sibling project untouched.
        assertThat(pageCount(ws, "beta")).isEqualTo(1);
        assertThat(projectRowExists(ws, "beta")).isTrue();

        // Audited with before/after identity.
        Integer audits = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log "
                        + "WHERE action = 'project.rename' AND workspace = ? AND project = 'renamed' "
                        + "  AND detail->'from'->>'project' = 'alpha' "
                        + "  AND detail->'to'->>'project' = 'renamed'",
                Integer.class, ws.value());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void renameOntoAnExistingSiblingIsRefused() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "one", "a.md"), "A", "one\n");
        seed(pageAt(ws, "two", "b.md"), "B", "two\n");

        assertThatThrownBy(() -> lifecycle.renameProject(
                Identity.ofProject(ws, ProjectId.of("one")), ProjectId.of("two")))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("already exists");

        // Nothing changed.
        assertThat(pageCount(ws, "one")).isEqualTo(1);
        assertThat(pageCount(ws, "two")).isEqualTo(1);
    }

    @Test
    void renameOfMissingProjectIsRefused() {
        WorkspaceId ws = freshWorkspace();
        assertThatThrownBy(() -> lifecycle.renameProject(
                Identity.ofProject(ws, ProjectId.of("ghost")), ProjectId.of("whatever")))
                .isInstanceOf(LifecycleException.class)
                .hasMessageContaining("does not exist");
    }

    // --- move -----------------------------------------------------------------------------------

    @Test
    void moveRelocatesProjectToAnotherWorkspaceWithWikiAndRows() {
        WorkspaceId ws1 = freshWorkspace();
        WorkspaceId ws2 = freshWorkspace();
        Identity p = pageAt(ws1, "proj", "concepts/x.md");
        seed(p, "X", "body\n");

        ProjectOpResult result = lifecycle.moveProject(
                Identity.ofProject(ws1, ProjectId.of("proj")), ws2, ProjectId.of("proj"));

        assertThat(result.op()).isEqualTo("move");
        assertThat(result.before().workspace().value()).isEqualTo(ws1.value());
        assertThat(result.after().workspace().value()).isEqualTo(ws2.value());

        assertThat(pageCount(ws1, "proj")).isZero();
        assertThat(pageCount(ws2, "proj")).isEqualTo(1);
        assertThat(pages.readLatest(pageAt(ws2, "proj", "concepts/x.md"))).isPresent();

        // Wiki followed across workspaces.
        assertThat(Files.isDirectory(wikiPaths.projectDir(pageAt(ws1, "proj", "concepts/x.md"))))
                .isFalse();
        assertThat(Files.isDirectory(wikiPaths.projectDir(pageAt(ws2, "proj", "concepts/x.md"))))
                .isTrue();

        // The FK followed too: the moved page's project row lives under ws2.
        Integer underWs2 = jdbc().queryForObject(
                "SELECT count(*) FROM pages p JOIN projects pr ON p.project_id = pr.id "
                        + "WHERE p.workspace = ? AND pr.workspace = ?",
                Integer.class, ws2.value(), ws2.value());
        assertThat(underWs2).isEqualTo(1);
    }

    @Test
    void moveCanRenameSimultaneously() {
        WorkspaceId ws1 = freshWorkspace();
        WorkspaceId ws2 = freshWorkspace();
        seed(pageAt(ws1, "old", "x.md"), "X", "body\n");

        lifecycle.moveProject(
                Identity.ofProject(ws1, ProjectId.of("old")), ws2, ProjectId.of("new"));

        assertThat(pageCount(ws2, "new")).isEqualTo(1);
        assertThat(pageCount(ws1, "old")).isZero();
    }

    // --- purge ----------------------------------------------------------------------------------

    @Test
    void purgeRemovesSubtreeRowsAndEmbeddingsAndIsIdempotentSiblingsSurvive() {
        WorkspaceId ws = freshWorkspace();
        Identity doomed = pageAt(ws, "doomed", "concepts/x.md");
        Identity sibling = pageAt(ws, "keep", "concepts/y.md");
        seed(doomed, "X", "doomed body\n");
        seed(sibling, "Y", "keep body\n");

        // Attach an embedding row to the doomed page to prove cascade deletion.
        UUID doomedPageId = pages.readLatest(doomed).orElseThrow().id().value();
        jdbc().update(
                "INSERT INTO page_embeddings (id, page_id, provider, model, dim, embedding) "
                        + "VALUES (?, ?, 'test', 'test', 1024, ?::vector)",
                com.agentmemory.core.Uuid7.randomUuid(), doomedPageId, oneKDimZeroVector());

        Path doomedDir = wikiPaths.projectDir(doomed);
        assertThat(Files.isDirectory(doomedDir)).isTrue();

        ProjectOpResult result = lifecycle.purgeProject(Identity.ofProject(ws, ProjectId.of("doomed")));
        assertThat(result.op()).isEqualTo("purge");
        assertThat(result.pagesAffected()).isEqualTo(1);

        // Rows gone (pages → cascade embeddings), project row gone.
        assertThat(pageCount(ws, "doomed")).isZero();
        assertThat(projectRowExists(ws, "doomed")).isFalse();
        Integer embeddings = jdbc().queryForObject(
                "SELECT count(*) FROM page_embeddings WHERE page_id = ?", Integer.class, doomedPageId);
        assertThat(embeddings).isZero();

        // Wiki subtree gone.
        assertThat(Files.exists(doomedDir)).isFalse();

        // Sibling intact.
        assertThat(pageCount(ws, "keep")).isEqualTo(1);
        assertThat(Files.isDirectory(wikiPaths.projectDir(sibling))).isTrue();

        // Idempotent: purging again is a no-op (no rows), still succeeds.
        ProjectOpResult again = lifecycle.purgeProject(Identity.ofProject(ws, ProjectId.of("doomed")));
        assertThat(again.pagesAffected()).isZero();

        // Audited.
        Integer audits = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log "
                        + "WHERE action = 'project.purge' AND workspace = ? AND project = 'doomed'",
                Integer.class, ws.value());
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }

    /** A pgvector text literal of 1024 zeros, for inserting a placeholder embedding. */
    private static String oneKDimZeroVector() {
        StringBuilder sb = new StringBuilder(1024 * 2 + 2).append('[');
        for (int i = 0; i < 1024; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('0');
        }
        return sb.append(']').toString();
    }
}
