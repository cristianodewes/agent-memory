package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.mcp.McpReadRepository;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * End-to-end consolidation tests (issue #19) over a throwaway {@code pgvector/pgvector:pg16} and a real
 * wiki git repo, proving the acceptance criteria:
 *
 * <ul>
 *   <li><b>supersession-on-consolidate</b> — consolidating creates/updates durable pages and a second
 *       consolidation of the same path supersedes the prior version (version chain #12).</li>
 *   <li><b>multi-page atomic fan-out</b> — {@code multiPage=true} writes several pages across folders
 *       in <em>one</em> git commit.</li>
 *   <li><b>atomic rollback</b> — if any page in a fan-out fails to write, the whole set rolls back: no
 *       partial rows and no commit (all-or-nothing).</li>
 *   <li><b>forward links resolve</b> — {@code [[links]]} the model writes between the fanned-out pages
 *       become live edges (#27).</li>
 *   <li><b>explore verbosity</b> — {@code memory_explore} calibrates its tier to staleness and the
 *       model sees that tier.</li>
 * </ul>
 *
 * <p>The {@link Consolidator}/{@link MemoryExplore} are built directly with a scripted
 * {@link TestDoubleProvider} over the autowired real {@link MemoryWriteService} /
 * {@link McpReadRepository} / {@link PageRepository}, so the LLM reply is deterministic while
 * persistence + the atomic fan-out are exercised for real. The offline {@code test} provider boots the
 * context (DD-005 gate); the wiki watcher is disabled so the test owns the files.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class ConsolidationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @org.junit.jupiter.api.io.TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    @Autowired MemoryWriteService writes;
    @Autowired McpReadRepository reads;
    @Autowired PageRepository pages;
    @Autowired SessionObservationReader reader;
    @Autowired WikiPaths wikiPaths;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- seeding -----------------------------------------------------------------------------------

    private record Proj(String ws, String proj, UUID wsId, UUID projId) {
        Scope scope() {
            return Scope.of(ws, proj);
        }
    }

    private Proj freshProject() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return new Proj(ws, proj, wsId, projId);
    }

    private SessionId seedSessionWithObservations(Proj p, String... prompts) {
        SessionId sid = SessionId.newId();
        jdbc().update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sid.value(), p.wsId(), p.projId(), p.ws(), p.proj(),
                java.sql.Timestamp.from(Instant.now()));
        for (String prompt : prompts) {
            jdbc().update(
                    "INSERT INTO observations (id, session_id, workspace_id, project_id, workspace, "
                            + "project, kind, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), sid.value(), p.wsId(), p.projId(), p.ws(), p.proj(),
                    "user-prompt", prompt, java.sql.Timestamp.from(Instant.now()));
        }
        return sid;
    }

    /** A {@link Consolidator} backed by a scripted LLM (returning {@code cannedJson}) over the real services. */
    private Consolidator consolidatorReturning(String cannedJson, AtomicReference<ChatRequest> captured) {
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> {
                    if (captured != null) {
                        captured.set(req);
                    }
                    return cannedJson;
                })
                .build();
        return new Consolidator(reader, llm, writes, reads);
    }

    private Consolidator consolidatorReturning(String cannedJson) {
        return consolidatorReturning(cannedJson, null);
    }

    private static String page(String folder, String slug, String title, String body) {
        return "{\"folder\":\"" + folder + "\",\"slug\":\"" + slug + "\",\"title\":\"" + title
                + "\",\"body\":\"" + body + "\"}";
    }

    private static String pagesJson(String... pageObjects) {
        return "{\"pages\":[" + String.join(",", pageObjects) + "]}";
    }

    private long countVersions(Proj p, String path) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ?",
                Long.class, p.ws(), p.proj(), path);
    }

    private Identity pageId(Proj p, String path) {
        return Identity.ofPage(WorkspaceId.of(p.ws()), ProjectId.of(p.proj()), PagePath.of(path));
    }

    // --- supersession ------------------------------------------------------------------------------

    @Test
    void consolidateWritesADurablePageAndASecondRunSupersedesIt() {
        Proj p = freshProject();
        SessionId s1 = seedSessionWithObservations(p, "explain how recall fuses signals");
        Consolidator first = consolidatorReturning(pagesJson(
                page("concepts", "recall", "Recall", "RRF fuses full-text and graph.")));

        ConsolidationOutcome o1 = first.consolidate(p.scope(), s1, false, MemoryWriteService.ACTOR_MCP);
        assertThat(o1.wrote()).isTrue();
        assertThat(o1.pages()).hasSize(1);
        PageRecord v1 = pages.readLatest(pageId(p, "concepts/recall.md")).orElseThrow();
        assertThat(v1.page().title()).isEqualTo("Recall");
        assertThat(v1.page().supersedes()).isNull();

        // A second consolidation onto the same path supersedes v1.
        SessionId s2 = seedSessionWithObservations(p, "recall now also uses vectors");
        Consolidator second = consolidatorReturning(pagesJson(
                page("concepts", "recall", "Recall v2", "RRF now also blends vector similarity.")));
        ConsolidationOutcome o2 = second.consolidate(p.scope(), s2, false, MemoryWriteService.ACTOR_MCP);
        assertThat(o2.wrote()).isTrue();

        PageRecord v2 = pages.readLatest(pageId(p, "concepts/recall.md")).orElseThrow();
        assertThat(v2.page().title()).isEqualTo("Recall v2");
        assertThat(v2.page().supersedes()).isEqualTo(v1.id());
        assertThat(countVersions(p, "concepts/recall.md")).isEqualTo(2);
    }

    @Test
    void singlePageRequestKeepsOnlyTheFirstPageEvenIfTheModelReturnsMore() {
        Proj p = freshProject();
        SessionId s = seedSessionWithObservations(p, "did some work");
        // Model returns two, but multiPage=false → only the first is written.
        Consolidator c = consolidatorReturning(pagesJson(
                page("concepts", "a", "A", "first"),
                page("decisions", "b", "B", "second")));
        ConsolidationOutcome o = c.consolidate(p.scope(), s, false, MemoryWriteService.ACTOR_MCP);
        assertThat(o.pages()).hasSize(1);
        assertThat(pages.readLatest(pageId(p, "concepts/a.md"))).isPresent();
        assertThat(pages.readLatest(pageId(p, "decisions/b.md"))).isEmpty();
    }

    // --- multi-page atomic fan-out -----------------------------------------------------------------

    @Test
    void multiPageFanOutWritesAllPagesInOneCommit() {
        Proj p = freshProject();
        SessionId s = seedSessionWithObservations(p, "big session touching many areas");
        Consolidator c = consolidatorReturning(pagesJson(
                page("concepts", "hybrid-recall", "Hybrid recall", "How recall fuses signals."),
                page("decisions", "pgvector", "Use pgvector", "Chose pgvector cosine."),
                page("gotchas", "crlf", "Windows CRLF", "Watch line endings on Windows.")));

        long commitsBefore = wikiCommitCount(p);
        ConsolidationOutcome o = c.consolidate(p.scope(), s, true, MemoryWriteService.ACTOR_MCP);

        assertThat(o.wrote()).isTrue();
        assertThat(o.pages()).hasSize(3);
        // All three pages are latest.
        assertThat(pages.readLatest(pageId(p, "concepts/hybrid-recall.md"))).isPresent();
        assertThat(pages.readLatest(pageId(p, "decisions/pgvector.md"))).isPresent();
        assertThat(pages.readLatest(pageId(p, "gotchas/crlf.md"))).isPresent();

        // Exactly ONE new commit for the whole fan-out (all-or-nothing, one commit).
        assertThat(wikiCommitCount(p) - commitsBefore).as("one commit for the fan-out").isEqualTo(1);

        // And all three files are present on disk.
        for (String path : new String[] {
                "concepts/hybrid-recall.md", "decisions/pgvector.md", "gotchas/crlf.md"}) {
            assertThat(Files.exists(wikiPaths.resolve(pageId(p, path)))).as(path).isTrue();
        }
    }

    // --- atomic rollback ---------------------------------------------------------------------------

    @Test
    void aFailedPageInAFanOutRollsBackTheWholeSetAndCommitsNothing() throws Exception {
        Proj p = freshProject();
        SessionId s = seedSessionWithObservations(p, "session that will fail to write");

        // Block the gotchas/ folder by occupying its directory path with a regular FILE, so writing
        // gotchas/x.md throws mid-fan-out (createDirectories fails on a file in the path).
        Path projDir = wikiPaths.resolve(pageId(p, "concepts/placeholder.md")).getParent().getParent();
        Files.createDirectories(projDir);
        Files.writeString(projDir.resolve("gotchas"), "i am a file, not a directory");

        Consolidator c = consolidatorReturning(pagesJson(
                page("decisions", "good", "Good", "this one writes fine first"),
                page("gotchas", "bad", "Bad", "this one cannot be written")));

        long commitsBefore = wikiCommitCount(p);
        assertThatThrownBy(() -> c.consolidate(p.scope(), s, true, MemoryWriteService.ACTOR_MCP))
                .isInstanceOf(RuntimeException.class);

        // Nothing persisted: neither the good page nor the bad page has a row (whole tx rolled back).
        assertThat(countVersions(p, "decisions/good.md")).as("good page rolled back").isZero();
        assertThat(countVersions(p, "gotchas/bad.md")).isZero();
        // No commit was created.
        assertThat(wikiCommitCount(p)).as("no commit on rollback").isEqualTo(commitsBefore);
        // The good page's file was cleaned up (not left orphaned on disk).
        assertThat(Files.exists(wikiPaths.resolve(pageId(p, "decisions/good.md"))))
                .as("partial file cleaned up").isFalse();
    }

    @Test
    void duplicatePathsInOneConsolidationAreRejectedBeforeAnyWrite() {
        Proj p = freshProject();
        SessionId s = seedSessionWithObservations(p, "work");
        Consolidator c = consolidatorReturning(pagesJson(
                page("concepts", "dup", "One", "first"),
                page("concepts", "dup", "Two", "second")));
        assertThatThrownBy(() -> c.consolidate(p.scope(), s, true, MemoryWriteService.ACTOR_MCP))
                .isInstanceOf(RuntimeException.class);
        assertThat(countVersions(p, "concepts/dup.md")).isZero();
    }

    // --- forward links resolve ---------------------------------------------------------------------

    @Test
    void forwardLinksBetweenFannedOutPagesResolve() {
        Proj p = freshProject();
        SessionId s = seedSessionWithObservations(p, "two linked pages");
        // The concepts page links forward to the decisions page emitted in the SAME fan-out.
        Consolidator c = consolidatorReturning(pagesJson(
                page("concepts", "recall", "Recall", "See [[decisions/pgvector]] for the store choice."),
                page("decisions", "pgvector", "Use pgvector", "We chose pgvector.")));
        ConsolidationOutcome o = c.consolidate(p.scope(), s, true, MemoryWriteService.ACTOR_MCP);
        assertThat(o.pages()).hasSize(2);

        // The link from concepts/recall.md to decisions/pgvector.md is recorded and resolved (#27):
        // a resolved edge has a non-null to_page_id pointing at the target's latest version.
        UUID toPageId = pages.readLatest(pageId(p, "decisions/pgvector.md")).orElseThrow().id().value();
        Long resolved = jdbc().queryForObject(
                "SELECT count(*) FROM links WHERE source_workspace = ? AND source_project = ? "
                        + "AND source_path = ? AND to_page_id = ?",
                Long.class, p.ws(), p.proj(), "concepts/recall.md", toPageId);
        assertThat(resolved).as("forward link resolved to the target page").isEqualTo(1L);
    }

    // --- explore verbosity -------------------------------------------------------------------------

    @Test
    void exploreCalibratesTierToStalenessAndModelSeesIt() {
        Proj p = freshProject();
        // Fresh activity: a session/observation just now → tier "fresh".
        seedSessionWithObservations(p, "just did something");

        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> {
                    captured.set(req);
                    return "You are up to date; the recall work is the current thread.";
                })
                .build();
        MemoryExplore explore = new MemoryExplore(llm, reads, pages);

        MemoryExplore.ExploreResult fresh = explore.explore(p.scope());
        assertThat(fresh.staleness()).isEqualTo("fresh");
        assertThat(fresh.digest()).isNotEmpty();
        // The model saw the staleness tier in its prompt (calibration signal).
        String prompt = captured.get().messages().get(captured.get().messages().size() - 1).content();
        assertThat(prompt).contains("staleness: fresh");

        // A project with no activity at all → tier "new".
        Proj empty = freshProject();
        MemoryExplore.ExploreResult none = explore.explore(empty.scope());
        assertThat(none.staleness()).isEqualTo("new");
    }

    @Test
    void exploreReportsStaleForAnOldProject() {
        Proj p = freshProject();
        // Backdate the only activity ~60 days so the project reads "stale".
        SessionId sid = SessionId.newId();
        jdbc().update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, now() - make_interval(days => 60))",
                sid.value(), p.wsId(), p.projId(), p.ws(), p.proj());
        jdbc().update(
                "INSERT INTO observations (id, session_id, workspace_id, project_id, workspace, project, "
                        + "kind, payload, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'user-prompt', 'old work', now() - make_interval(days => 60))",
                UUID.randomUUID(), sid.value(), p.wsId(), p.projId(), p.ws(), p.proj());

        MemoryExplore explore = new MemoryExplore(
                TestDoubleProvider.builder().chatResponder(r -> "Long time no see; here is the catch-up.").build(),
                reads, pages);
        assertThat(explore.explore(p.scope()).staleness()).isEqualTo("stale");
    }

    /** Count commits in the project's wiki git repo (whole-repo log; one shared wiki repo per data dir). */
    private long wikiCommitCount(Proj p) {
        Path wikiDir = wikiPaths.wikiDir();
        try (var git = org.eclipse.jgit.api.Git.open(wikiDir.toFile())) {
            long n = 0;
            for (var ignored : git.log().call()) {
                n++;
            }
            return n;
        } catch (Exception e) {
            // No commits yet / repo not initialized as expected.
            return 0;
        }
    }
}
