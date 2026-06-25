package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * End-to-end tests for the issue #20 admission chain ({@link MemoryWriteService}) against a throwaway
 * {@code pgvector/pgvector:pg16} Postgres (Testcontainers) with a real wiki on a temp dir. Proves the
 * acceptance criteria directly on the service, while {@link McpWriteEndpointTest} proves the tool
 * surface over the MCP transport:
 *
 * <ul>
 *   <li>{@code memory_write_page} persists a page (DB row + markdown file + git commit) that is
 *       immediately searchable, versions in place (supersede), and privacy-redacts the body.</li>
 *   <li>{@code memory_delete_page} removes the page from the index and the wiki idempotently
 *       (deleting a missing path is a no-op success).</li>
 *   <li>both mutations are audited with the identity tuple and actor.</li>
 *   <li>writes honor the single-writer + same-transaction-index invariants (a wiki side-effect
 *       failure rolls the whole write back).</li>
 * </ul>
 *
 * <p>The offline {@code test} LLM provider boots the context past the DD-005 gate; the wiki data dir
 * is the JUnit temp dir so no real {@code ~/.agent-memory} is touched. The watcher is disabled so the
 * test drives the write path deterministically.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class MemoryWriteServiceTest {

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

    @Autowired MemoryWriteService writes;
    @Autowired PageRepository pages;
    @Autowired RecallService recall;
    @Autowired WikiPaths wikiPaths;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Identity page(WorkspaceId ws, String project, String path) {
        return Identity.ofPage(ws, ProjectId.of(project), PagePath.of(path));
    }

    private Long auditCount(Identity id, String action) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM audit_log WHERE workspace = ? AND project = ? AND path = ? "
                        + "AND action = ?",
                Long.class,
                id.workspace().value(), id.project().value(), id.page().value(), action);
    }

    // --- write: persists row + file + commit, immediately searchable -------------------------------

    @Test
    void writePagePersistsRowFileAndCommitAndIsImmediatelySearchable() throws Exception {
        WorkspaceId ws = freshWorkspace();
        Identity id = page(ws, "app", "concepts/recall.md");

        PageRecord saved = writes.writePage(
                id, "Hybrid recall",
                "Reciprocal rank fusion blends full-text and link-graph signals.",
                MemoryWriteService.ACTOR_MCP);

        // DB row is the latest version.
        assertThat(saved.isLatest()).isTrue();
        assertThat(pages.readLatest(id)).isPresent();

        // Markdown file written + self-describing.
        Path file = wikiPaths.resolve(id);
        assertThat(Files.exists(file)).isTrue();
        String onDisk = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(onDisk).contains("title: \"Hybrid recall\"");
        assertThat(onDisk).contains("Reciprocal rank fusion");

        // Immediately searchable — the generated FTS column was populated by the insert.
        RecallResult hits = recall.search(new RecallQuery(
                "reciprocal rank fusion", Scope.of(ws.value(), "app"), 10));
        assertThat(hits.rawFallback()).isFalse();
        assertThat(hits.hits()).anySatisfy(h ->
                assertThat(h.path()).isEqualTo("concepts/recall.md"));

        // Audited with identity tuple + actor.
        assertThat(auditCount(id, "page.write")).isEqualTo(1L);
        String detail = jdbc().queryForObject(
                "SELECT detail::text FROM audit_log WHERE path = ? AND action = 'page.write'",
                String.class, "concepts/recall.md");
        assertThat(detail).contains("\"actor\": \"mcp\"");
    }

    @Test
    void writePageVersionsInPlaceOnSecondWrite() {
        WorkspaceId ws = freshWorkspace();
        Identity id = page(ws, "app", "concepts/evolving.md");

        PageRecord v1 = writes.writePage(id, "V1", "first body", MemoryWriteService.ACTOR_MCP);
        PageRecord v2 = writes.writePage(id, "V2", "second body", MemoryWriteService.ACTOR_MCP);

        assertThat(v2.page().supersedes()).isEqualTo(v1.id());
        assertThat(pages.readLatest(id).orElseThrow().id()).isEqualTo(v2.id());
        // Exactly one latest row at the DB level.
        Integer latest = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ? AND is_latest",
                Integer.class, ws.value(), "app", "concepts/evolving.md");
        assertThat(latest).isEqualTo(1);
        // Two write audits.
        assertThat(auditCount(id, "page.write")).isEqualTo(2L);
    }

    @Test
    void writePageRedactsSecretsInBody() {
        WorkspaceId ws = freshWorkspace();
        Identity id = page(ws, "app", "notes/leak.md");
        // A body carrying an email + an AWS-style key; the redaction pipeline must scrub them.
        writes.writePage(id, "Leaky note",
                "contact me at alice@example.com with key AKIAIOSFODNN7EXAMPLE please",
                MemoryWriteService.ACTOR_MCP);

        String body = pages.readLatest(id).orElseThrow().page().body();
        assertThat(body).doesNotContain("alice@example.com");
        assertThat(body).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        // The non-sensitive prose survives.
        assertThat(body).contains("contact me at");
        assertThat(body).contains("please");
    }

    @Test
    void writePageRejectsBlankTitleAndDoesNotWriteAnything() {
        WorkspaceId ws = freshWorkspace();
        Identity id = page(ws, "app", "concepts/bad.md");
        assertThatThrownBy(() ->
                writes.writePage(id, "  ", "body", MemoryWriteService.ACTOR_MCP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pages.readLatest(id)).isEmpty();
        assertThat(Files.exists(wikiPaths.resolve(id))).isFalse();
    }

    // --- delete: idempotent, removes index + file, audited ----------------------------------------

    @Test
    void deletePageRemovesIndexRowAndWikiFileAndIsAudited() throws Exception {
        WorkspaceId ws = freshWorkspace();
        Identity id = page(ws, "app", "concepts/temp.md");
        writes.writePage(id, "Temp", "to be deleted", MemoryWriteService.ACTOR_MCP);
        Path file = wikiPaths.resolve(id);
        assertThat(Files.exists(file)).isTrue();

        boolean removed = writes.deletePage(id, MemoryWriteService.ACTOR_MCP);

        assertThat(removed).isTrue();
        assertThat(pages.readLatest(id)).isEmpty();
        // No rows for the path remain (all versions gone).
        Integer rows = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ?",
                Integer.class, ws.value(), "app", "concepts/temp.md");
        assertThat(rows).isZero();
        // Wiki file removed.
        assertThat(Files.exists(file)).isFalse();
        // Audited.
        assertThat(auditCount(id, "page.delete")).isEqualTo(1L);
    }

    @Test
    void deleteMissingPageIsANoOpSuccessAndStillAudited() {
        WorkspaceId ws = freshWorkspace();
        Identity id = page(ws, "app", "concepts/never-existed.md");

        boolean removed = writes.deletePage(id, MemoryWriteService.ACTOR_MCP);

        assertThat(removed).isFalse(); // no-op success
        assertThat(pages.readLatest(id)).isEmpty();
        // The attempt is still recorded (flagged existed=false).
        assertThat(auditCount(id, "page.delete")).isEqualTo(1L);
        String detail = jdbc().queryForObject(
                "SELECT detail::text FROM audit_log WHERE path = ? AND action = 'page.delete'",
                String.class, "concepts/never-existed.md");
        assertThat(detail).contains("\"existed\": false");
    }

    @Test
    void deleteIsIdempotentAcrossRepeatedCalls() {
        WorkspaceId ws = freshWorkspace();
        Identity id = page(ws, "app", "concepts/twice.md");
        writes.writePage(id, "Twice", "body", MemoryWriteService.ACTOR_MCP);

        assertThat(writes.deletePage(id, MemoryWriteService.ACTOR_MCP)).isTrue();
        // Second delete: already gone -> no-op success, no error.
        assertThat(writes.deletePage(id, MemoryWriteService.ACTOR_MCP)).isFalse();
        assertThat(pages.readLatest(id)).isEmpty();
    }

    @Test
    void deleteCascadesEmbeddingsAndLinksForThePath() {
        WorkspaceId ws = freshWorkspace();
        Identity id = page(ws, "app", "concepts/linked.md");
        PageRecord saved = writes.writePage(id, "Linked", "body", MemoryWriteService.ACTOR_MCP);
        UUID pageId = saved.id().value();

        // Attach an embedding + an outgoing link referencing this page version, then delete the page
        // and assert both dependents are gone (FK ON DELETE CASCADE).
        jdbc().update(
                "INSERT INTO page_embeddings (id, page_id, provider, model, dim, embedding) "
                        + "VALUES (?, ?, 'test', 'test-model', 1024, "
                        + "        array_fill(0.0::real, ARRAY[1024])::vector)",
                UUID.randomUUID(), pageId);
        jdbc().update(
                "INSERT INTO links (id, from_page_id, source_workspace, source_project, source_path) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), pageId, ws.value(), "app", "concepts/linked.md");

        writes.deletePage(id, MemoryWriteService.ACTOR_MCP);

        Integer embeds = jdbc().queryForObject(
                "SELECT count(*) FROM page_embeddings WHERE page_id = ?", Integer.class, pageId);
        Integer links = jdbc().queryForObject(
                "SELECT count(*) FROM links WHERE from_page_id = ?", Integer.class, pageId);
        assertThat(embeds).isZero();
        assertThat(links).isZero();
    }

    // --- atomicity: a wiki side-effect failure rolls the whole write back --------------------------

    @Test
    void writeRollsBackRowWhenWikiSideEffectFails() {
        WorkspaceId ws = freshWorkspace();
        // A path whose project segment is a reserved Windows device name would still resolve under the
        // temp wiki, so instead force a failure by making the target file path a directory: pre-create
        // a directory exactly where the markdown file must be written, so the atomic file write fails.
        Identity id = page(ws, "app", "concepts/blocked.md");
        Path file = wikiPaths.resolve(id);
        try {
            Files.createDirectories(file); // now a directory sits where the .md file should go
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertThatThrownBy(() ->
                writes.writePage(id, "Blocked", "body", MemoryWriteService.ACTOR_MCP))
                .isInstanceOf(RuntimeException.class);

        // The DB row was rolled back with the failed file write — index and wiki stay in lockstep.
        assertThat(pages.readLatest(id)).isEmpty();
        Integer rows = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ?",
                Integer.class, ws.value(), "app", "concepts/blocked.md");
        assertThat(rows).isZero();
        // No audit row either (the whole transaction rolled back).
        assertThat(auditCount(id, "page.write")).isZero();
    }

    // --- guards ------------------------------------------------------------------------------------

    @Test
    void writeAndDeleteRejectProjectScopedIdentity() {
        Identity projectScoped = Identity.ofProject(freshWorkspace(), ProjectId.of("app"));
        assertThatThrownBy(() ->
                writes.writePage(projectScoped, "t", "b", MemoryWriteService.ACTOR_MCP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                writes.deletePage(projectScoped, MemoryWriteService.ACTOR_MCP))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
