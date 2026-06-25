package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.lifecycle.ProcessLock;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiPaths;
import com.agentmemory.wiki.WikiWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * End-to-end wiring test for the lifecycle HTTP surface (issue #33): it drives the real
 * {@link LifecycleController} bean (autowired from the full context) so the controller's
 * {@code ObjectProvider<ProjectLifecycleService>} / {@code ObjectProvider<ResetService>} must actually
 * resolve the wired services — a dormant/unwired endpoint would answer {@code 503} and fail here,
 * rather than silently passing as a direct service-call test would. The process lock is <em>enabled</em>
 * so the {@code POST /reset} guard (invariant #9) is exercised on the real path: the running test JVM
 * holds the data dir, so reset refuses ({@code 409}) without {@code force} and performs with it.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false",
    "agent-memory.lifecycle.process-lock-enabled=true"
})
@Testcontainers
class LifecycleControllerIntegrationTest {

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

    @Autowired LifecycleController controller; // the bean under test (proves the endpoint is wired)
    @Autowired PageRepository pages;
    @Autowired WikiWriter wikiWriter;
    @Autowired WikiPaths wikiPaths;
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

    private void seed(Identity id, String title, String body) {
        pages.create(id, title, body, wikiWriter.callbackFor("seed: " + id.page().value()));
    }

    private long pageCount(WorkspaceId ws, String project) {
        Long n = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ?",
                Long.class, ws.value(), project);
        return n == null ? 0 : n;
    }

    // --- rename through the endpoint ------------------------------------------------------------

    @Test
    void renameEndpointDrivesTheWiredServiceAndReturnsBeforeAfterIdentity() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "alpha", "concepts/x.md"), "X", "alpha body\n");

        ResponseEntity<Map<String, Object>> resp = controller.rename(
                new LifecycleController.RenameRequest(ws.value(), "alpha", "renamed"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("op", "rename");
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) resp.getBody().get("after");
        assertThat(after).containsEntry("project", "renamed");

        // The real index changed (the endpoint did work, not a 503 no-op).
        assertThat(pageCount(ws, "alpha")).isZero();
        assertThat(pageCount(ws, "renamed")).isEqualTo(1);
    }

    @Test
    void renameEndpointReturns409OnDestinationCollision() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "one", "a.md"), "A", "one\n");
        seed(pageAt(ws, "two", "b.md"), "B", "two\n");

        ResponseEntity<Map<String, Object>> resp = controller.rename(
                new LifecycleController.RenameRequest(ws.value(), "one", "two"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsEntry("status", "conflict");
    }

    // --- purge through the endpoint -------------------------------------------------------------

    @Test
    void purgeEndpointRemovesRowsAndSubtree() {
        WorkspaceId ws = freshWorkspace();
        Identity doomed = pageAt(ws, "doomed", "concepts/x.md");
        seed(doomed, "X", "body\n");
        Path doomedDir = wikiPaths.projectDir(doomed);
        assertThat(Files.isDirectory(doomedDir)).isTrue();

        ResponseEntity<Map<String, Object>> resp = controller.purge(
                new LifecycleController.PurgeRequest(ws.value(), "doomed"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pageCount(ws, "doomed")).isZero();
        assertThat(Files.exists(doomedDir)).isFalse();
    }

    // --- reset through the endpoint: the live-process guard is on the real path ------------------

    @Test
    void resetEndpointRefusesWithoutForceWhileThisProcessHoldsTheDataDir() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "p", "x.md"), "X", "body\n");
        long before = pageCount(ws, "p");
        assertThat(before).isGreaterThan(0);

        // Sanity: a live holder really exists for this data dir (the startup ProcessLock).
        assertThat(ProcessLock.detectAnyLiveHolder(dataDir)).isPresent();

        ResponseEntity<Map<String, Object>> resp = controller.reset(
                new LifecycleController.ResetRequest(false));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsEntry("performed", false);
        assertThat(String.valueOf(resp.getBody().get("reason"))).contains("live");
        // Nothing wiped — the guard is genuinely on the reset path, not a dead helper.
        assertThat(pageCount(ws, "p")).isEqualTo(before);
    }

    @Test
    void resetEndpointPerformsWithForce() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "p2", "y.md"), "Y", "body\n");
        assertThat(pageCount(ws, "p2")).isGreaterThan(0);

        ResponseEntity<Map<String, Object>> resp = controller.reset(
                new LifecycleController.ResetRequest(true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("performed", true);
        // Everything wiped.
        Long total = jdbc().queryForObject("SELECT count(*) FROM pages", Long.class);
        assertThat(total).isZero();
    }
}
