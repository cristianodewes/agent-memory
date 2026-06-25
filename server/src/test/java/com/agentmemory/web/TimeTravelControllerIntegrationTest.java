package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiGit;
import com.agentmemory.wiki.WikiPaths;
import com.agentmemory.wiki.WikiWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for issue #34 driven through the real {@link TimeTravelController} bean (the
 * acceptance criterion is that the endpoints are wired and invocable, not just that the services
 * exist). Backed by a Testcontainers {@code pgvector/pgvector:pg16} Postgres and a {@code @TempDir}
 * data dir so the wiki has its own git repo.
 *
 * <p>Covers, through the controller:
 * <ul>
 *   <li><b>checkpoints</b> — recent wiki commits are listed.</li>
 *   <li><b>restore-page round-trip</b> — a page edited to v2 is restored to v1 from the v1 commit and
 *       the DB index reflects v1 again.</li>
 *   <li><b>backup/restore equivalence</b> — a backup taken with the source writable reproduces the
 *       capture state after the rows are mutated and then restored.</li>
 *   <li><b>destructive-restore live-process check</b> — with the data dir's process lock held (a live
 *       holder), an un-forced restore is refused with 409 (invariant #9); {@code force} overrides.</li>
 * </ul>
 *
 * <p>The process lock is disabled by default in the test profile (shared data dir across contexts);
 * the live-process-check case re-enables it by acquiring a real {@code ProcessLock} on the temp data
 * dir for the duration of the assertion.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class TimeTravelControllerIntegrationTest {

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

    @Autowired TimeTravelController controller;
    @Autowired PageRepository pages;
    @Autowired WikiWriter wikiWriter;
    @Autowired WikiPaths wikiPaths;
    @Autowired WikiGit wikiGit;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    private Identity pageAt(WorkspaceId ws, String path) {
        return Identity.ofPage(ws, ProjectId.of("proj"), PagePath.of(path));
    }

    private PageRecord seed(Identity id, String title, String body) {
        return pages.create(id, title, body, wikiWriter.callbackFor("seed: " + id.page().value()));
    }

    private String latestBody(Identity id) {
        return pages.readLatest(id).map(r -> r.page().body()).orElse(null);
    }

    // --- checkpoints -----------------------------------------------------------------------------

    @Test
    void checkpointsListsRecentWikiCommits() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "concepts/a.md"), "A", "alpha body\n");
        seed(pageAt(ws, "concepts/b.md"), "B", "bravo body\n");

        ResponseEntity<Map<String, Object>> resp = controller.checkpoints(20);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commits = (List<Map<String, Object>>) body.get("checkpoints");
        // At least the two seed commits (+ the wiki's root init commit).
        assertThat(commits.size()).isGreaterThanOrEqualTo(3);
        // Each checkpoint carries a sha + message.
        assertThat(commits.get(0)).containsKeys("sha", "shortSha", "message", "author", "committedAt");
        assertThat(commits.stream().map(c -> (String) c.get("message")))
                .anyMatch(m -> m.contains("seed: concepts/b.md"));
    }

    // --- restore-page round-trip -----------------------------------------------------------------

    @Test
    void restorePageRestoresAPriorRevisionAndReindexesIt() throws Exception {
        WorkspaceId ws = freshWorkspace();
        Identity page = pageAt(ws, "concepts/recall.md");

        // v1, capture its commit sha, then overwrite with v2.
        seed(page, "Recall v1", "Recall version ONE body.\n");
        String v1Sha = wikiGit.git().log().setMaxCount(1).call().iterator().next().getName();
        seed(page, "Recall v2", "Recall version TWO body, totally different.\n");
        assertThat(latestBody(page)).contains("version TWO");

        // Restore v1 through the endpoint.
        ResponseEntity<Map<String, Object>> resp = controller.restorePage(
                new TimeTravelController.RestorePageRequest(
                        ws.value(), "proj", "concepts/recall.md", v1Sha));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("changed")).isEqualTo(true);
        assertThat((Integer) body.get("pagesIndexed")).isGreaterThanOrEqualTo(1);

        // The DB index now reflects v1 again (restore wrote the file + reindexed it).
        assertThat(latestBody(page)).contains("version ONE");
        // And the file on disk is v1's exact content.
        Path file = wikiPaths.resolve(page);
        assertThat(readFile(file)).contains("Recall version ONE body.");
    }

    @Test
    void restorePageWithUnknownRevisionIsAConflict() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "concepts/x.md"), "X", "body\n");
        ResponseEntity<Map<String, Object>> resp = controller.restorePage(
                new TimeTravelController.RestorePageRequest(
                        ws.value(), "proj", "concepts/x.md", "deadbeefdeadbeef"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- backup / restore equivalence ------------------------------------------------------------

    @Test
    void backupThenMutateThenRestoreReproducesCaptureState() {
        // Seed identity + a session + observations directly (primary capture state covered by backup).
        WorkspaceId ws = freshWorkspace();
        UUID workspaceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?::uuid, ?)",
                workspaceId, ws.value());
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) "
                + "VALUES (?::uuid, ?::uuid, ?, 'proj')", projectId, workspaceId, ws.value());
        jdbc().update("INSERT INTO sessions (id, workspace_id, project_id, workspace, project, "
                + "agent, started_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'proj', 'claude', now())",
                sessionId, workspaceId, projectId, ws.value());
        jdbc().update("INSERT INTO observations (id, session_id, workspace_id, project_id, "
                + "workspace, project, kind, payload, created_at) "
                + "VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, 'proj', 'user-prompt', ?, now())",
                UUID.randomUUID(), sessionId, workspaceId, projectId, ws.value(), "remember THIS fact");

        int sessionsBefore = countWhere("sessions", ws);
        int obsBefore = countWhere("observations", ws);
        assertThat(sessionsBefore).isEqualTo(1);
        assertThat(obsBefore).isEqualTo(1);

        // Back up (source stays writable — we mutate right after).
        Path archive = dataDir.resolve("backups").resolve("bk-" + ws.value() + ".tar.gz");
        ResponseEntity<Map<String, Object>> backupResp = controller.backup(
                new TimeTravelController.BackupRequest(archive.toString()));
        assertThat(backupResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Files.exists(archive)).isTrue();
        assertThat((Long) backupResp.getBody().get("bytes")).isGreaterThan(0L);

        // Mutate AFTER the backup: delete this workspace's capture rows. (Source was writable.)
        jdbc().update("DELETE FROM observations WHERE workspace = ?", ws.value());
        jdbc().update("DELETE FROM sessions WHERE workspace = ?", ws.value());
        assertThat(countWhere("sessions", ws)).isZero();
        assertThat(countWhere("observations", ws)).isZero();

        // Restore (process lock disabled in this profile → no live holder → performed).
        ResponseEntity<Map<String, Object>> restoreResp = controller.restore(
                new TimeTravelController.RestoreRequest(archive.toString(), false));
        assertThat(restoreResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restoreResp.getBody().get("performed")).isEqualTo(true);

        // Capture state is reproduced.
        assertThat(countWhere("sessions", ws)).isEqualTo(sessionsBefore);
        assertThat(countWhere("observations", ws)).isEqualTo(obsBefore);
        assertThat(jdbc().queryForObject(
                "SELECT payload FROM observations WHERE workspace = ?", String.class, ws.value()))
                .isEqualTo("remember THIS fact");
    }

    // --- destructive-restore live-process check (invariant #9) -----------------------------------

    @Test
    void restoreIsRefusedWhileALiveProcessHoldsTheDataDir() {
        // Produce a valid archive first (so the refusal is about the lock, not the file).
        Path archive = dataDir.resolve("backups").resolve("bk-guard.tar.gz");
        controller.backup(new TimeTravelController.BackupRequest(archive.toString()));

        // Hold the data-dir lock to simulate a live agent-memory process.
        try (var lock = new com.agentmemory.lifecycle.ProcessLock(dataDir)) {
            lock.acquire();

            // Un-forced restore is refused with 409 (a live holder is present).
            ResponseEntity<Map<String, Object>> refused = controller.restore(
                    new TimeTravelController.RestoreRequest(archive.toString(), false));
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("performed")).isEqualTo(false);
            assertThat(refused.getBody().get("liveHolderPid")).isNotNull();

            // force=true overrides the guard and performs the restore.
            ResponseEntity<Map<String, Object>> forced = controller.restore(
                    new TimeTravelController.RestoreRequest(archive.toString(), true));
            assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(forced.getBody().get("performed")).isEqualTo(true);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private int countWhere(String table, WorkspaceId ws) {
        Integer n = jdbc().queryForObject(
                "SELECT count(*) FROM " + table + " WHERE workspace = ?", Integer.class, ws.value());
        return n == null ? 0 : n;
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
