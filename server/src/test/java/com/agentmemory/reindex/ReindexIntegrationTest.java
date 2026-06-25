package com.agentmemory.reindex;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.MarkdownDocument;
import com.agentmemory.wiki.PageFrontmatter;
import com.agentmemory.wiki.PageKind;
import com.agentmemory.wiki.WikiGit;
import com.agentmemory.wiki.WikiPaths;
import com.agentmemory.wiki.WikiWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * Integration tests for issue #14's acceptance criteria against a Testcontainers
 * {@code pgvector/pgvector:pg16} Postgres:
 *
 * <ul>
 *   <li><b>Drop-and-rebuild equivalence</b> — drop {@code pages}/{@code links}, run a full reindex
 *       from {@code wiki/}, and the FTS search results + link graph come back equivalent.</li>
 *   <li><b>Links re-resolved</b> — a forward link written before its target exists resolves once the
 *       target is indexed; cross-project targets are recorded.</li>
 *   <li><b>Full vs incremental parity</b> — applying file edits then reindexing incrementally yields
 *       the same per-workspace state as a from-scratch full rebuild.</li>
 *   <li><b>Re-embed is opt-in</b> — requesting re-embed with no embedder configured is a no-op.</li>
 *   <li><b>Capture tables untouched</b> — an {@code observations} row survives a full reindex
 *       (DD-002: capture tables are primary, not derived).</li>
 * </ul>
 *
 * <p>The watcher is disabled so reindex (not the watcher) is what processes on-disk edits; the data
 * dir is a JUnit temp dir so the wiki gets its own git repo and no real {@code ~/.agent-memory} is
 * touched. The offline {@code test} LLM/embeddings providers let the full context boot.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class ReindexIntegrationTest {

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
    @Autowired WikiGit wikiGit;
    @Autowired ReindexService reindex;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** A fresh, unique workspace per test keeps the shared container/wiki isolated between cases. */
    private static WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Identity pageAt(WorkspaceId ws, String path) {
        return Identity.ofPage(ws, ProjectId.of("proj"), PagePath.of(path));
    }

    /** Seed a page the normal way: DB row + atomic markdown file + git commit (the #13 path). */
    private PageRecord seed(Identity id, String title, String body) {
        return pages.create(id, title, body, wikiWriter.callbackFor("seed: " + id.page().value()));
    }

    /** Write a page file directly on disk (simulating an external edit), bypassing the app/DB. */
    private void writeFileDirectly(Identity id, String title, String body) throws Exception {
        Path file = wikiPaths.resolve(id);
        Files.createDirectories(file.getParent());
        MarkdownDocument doc = new MarkdownDocument(
                new PageFrontmatter(
                        title, PageKind.fromPath(id.page()), false, null,
                        id.workspace(), id.project(), id.page(),
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z")),
                body);
        Files.writeString(file, doc.render(), StandardCharsets.UTF_8);
    }

    /** The set of latest page paths in a workspace whose FTS matches {@code term}. */
    private List<String> ftsPaths(WorkspaceId ws, String term) {
        return jdbc().queryForList(
                "SELECT path FROM pages "
                        + "WHERE workspace = ? AND project = 'proj' AND is_latest "
                        + "  AND search_vector @@ plainto_tsquery('english', ?) "
                        + "ORDER BY path",
                String.class, ws.value(), term);
    }

    /** Map of path → latest body for a workspace (the comparable end-state of the page index). */
    private Map<String, String> latestBodies(WorkspaceId ws) {
        return jdbc().queryForList(
                        "SELECT path, body FROM pages "
                                + "WHERE workspace = ? AND project = 'proj' AND is_latest "
                                + "ORDER BY path",
                        ws.value())
                .stream()
                .collect(Collectors.toMap(row -> (String) row.get("path"), row -> (String) row.get("body")));
    }

    /** The number of links sourced from a given page identity (any resolution state). */
    private long linkCountFrom(Identity source) {
        Long n = jdbc().queryForObject(
                "SELECT count(*) FROM links "
                        + "WHERE source_workspace = ? AND source_project = ? AND source_path = ?",
                Long.class,
                source.workspace().value(), source.project().value(), source.page().value());
        return n == null ? 0L : n;
    }

    /** Set of resolved link edges "fromPath -> targetPath" within a workspace. */
    private List<String> resolvedEdges(WorkspaceId ws) {
        return jdbc().queryForList(
                "SELECT source_path || ' -> ' || target_path AS edge FROM links "
                        + "WHERE source_workspace = ? AND target_resolved "
                        + "ORDER BY edge",
                String.class, ws.value());
    }

    // --- drop-and-rebuild equivalence ----------------------------------------------------------

    @Test
    void droppingTheIndexAndReindexingReproducesSearchAndLinks() {
        WorkspaceId ws = freshWorkspace();
        Identity recall = pageAt(ws, "concepts/recall.md");
        Identity fusion = pageAt(ws, "concepts/fusion.md");
        Identity storage = pageAt(ws, "decisions/storage.md");

        seed(recall, "Hybrid recall",
                "Reciprocal rank fusion blends FTS, graph and vector. See [[concepts/fusion.md]].\n");
        seed(fusion, "Rank fusion", "Reciprocal rank fusion combines ranked lists.\n");
        seed(storage, "Storage decision", "Postgres is the derived index; wiki is source of truth.\n");

        // Baseline search results (pre-drop).
        List<String> recallHits = ftsPaths(ws, "reciprocal fusion");
        List<String> storageHits = ftsPaths(ws, "derived index");
        assertThat(recallHits).contains("concepts/recall.md", "concepts/fusion.md");
        assertThat(storageHits).containsExactly("decisions/storage.md");

        // Drop the DERIVED index entirely (pages + links). Capture tables are untouched.
        jdbc().update("DELETE FROM links");
        jdbc().update("DELETE FROM pages");
        assertThat(latestBodies(ws)).isEmpty();

        // Rebuild from the wiki files alone.
        ReindexReport report = reindex.reindex(ReindexOptions.full());
        assertThat(report.mode()).isEqualTo(ReindexMode.FULL);
        assertThat(report.skipped()).isEmpty();

        // Search results are equivalent to the pre-drop baseline.
        assertThat(ftsPaths(ws, "reciprocal fusion")).isEqualTo(recallHits);
        assertThat(ftsPaths(ws, "derived index")).isEqualTo(storageHits);

        // The wikilink graph was rebuilt and the link resolved to the (existing) target.
        assertThat(linkCountFrom(recall)).isEqualTo(1);
        assertThat(resolvedEdges(ws)).contains("concepts/recall.md -> concepts/fusion.md");
    }

    // --- forward / deferred link resolution ----------------------------------------------------

    @Test
    void forwardLinkResolvesOnceTargetIsIndexedInSameRun() throws Exception {
        WorkspaceId ws = freshWorkspace();
        // Author A linking to B, where B is alphabetically AFTER A — but also create B so the run has
        // both. The link from A is written before B's row when A sorts first; resolveDeferred() closes
        // it. Use direct files + full reindex so ordering is purely the on-disk walk.
        Identity a = pageAt(ws, "concepts/aaa.md");
        Identity b = pageAt(ws, "concepts/zzz.md");
        writeFileDirectly(a, "A", "links forward to [[concepts/zzz.md]]\n");
        writeFileDirectly(b, "B", "the target\n");

        ReindexReport report = reindex.reindex(ReindexOptions.full());
        assertThat(report.linksWritten()).isGreaterThanOrEqualTo(1);

        assertThat(resolvedEdges(ws)).contains("concepts/aaa.md -> concepts/zzz.md");
    }

    @Test
    void crossProjectLinkIsRecordedAndResolvesWhenTargetExists() {
        WorkspaceId ws = freshWorkspace();
        Identity src = pageAt(ws, "concepts/src.md");
        // Target lives in a DIFFERENT project of the same workspace, named via the canonical
        // sibling-project scope form [[project:path]] (#27 grammar).
        Identity tgt = Identity.ofPage(ws, ProjectId.of("otherproj"), PagePath.of("concepts/tgt.md"));

        seed(src, "Src", "see [[otherproj:concepts/tgt.md]]\n");
        seed(tgt, "Tgt", "the cross-project target\n");

        reindex.reindex(ReindexOptions.full());

        List<String> edges = jdbc().queryForList(
                "SELECT target_resolved::text FROM links "
                        + "WHERE source_workspace = ? AND source_path = 'concepts/src.md' "
                        + "  AND target_project = 'otherproj' AND target_path = 'concepts/tgt.md'",
                String.class, ws.value());
        assertThat(edges).containsExactly("true");
    }

    // --- full vs incremental parity ------------------------------------------------------------

    @Test
    void incrementalRebuildMatchesAFullRebuildForTheChangedSet() throws Exception {
        WorkspaceId ws = freshWorkspace();
        Identity keep = pageAt(ws, "concepts/keep.md");
        Identity edit = pageAt(ws, "concepts/edit.md");
        Identity gone = pageAt(ws, "concepts/gone.md");
        Identity added = pageAt(ws, "concepts/added.md");

        // Initial seed (files + rows + a commit), then build the baseline index with a full reindex —
        // incremental is defined as updating an ALREADY-built index, so unchanged pages' links must
        // already exist (they are not extracted at seed time). Capture the ref to diff from after that.
        seed(keep, "Keep", "unchanged body links [[concepts/edit.md]]\n");
        seed(edit, "Edit", "original body\n");
        seed(gone, "Gone", "will be deleted\n");
        reindex.reindex(ReindexOptions.full());
        String baseRef = wikiGit.git().getRepository().resolve("HEAD").getName();

        // Apply on-disk changes (uncommitted edits are still seen by changedSince's working-tree diff):
        // modify 'edit', delete 'gone', add a brand-new 'added' that links to 'keep'.
        writeFileDirectly(edit, "Edit", "rewritten body with new words\n");
        Files.delete(wikiPaths.resolve(gone));
        writeFileDirectly(added, "Added", "fresh page linking [[concepts/keep.md]]\n");

        // Incremental from the base ref.
        ReindexReport inc = reindex.reindex(ReindexOptions.incremental(baseRef));
        assertThat(inc.mode()).isEqualTo(ReindexMode.INCREMENTAL);
        assertThat(inc.pagesIndexed()).isGreaterThanOrEqualTo(2); // edit + added
        assertThat(inc.pagesDeleted()).isEqualTo(1);              // gone

        Map<String, String> afterIncremental = latestBodies(ws);
        List<String> edgesAfterIncremental = resolvedEdges(ws);

        // Now force a from-scratch FULL rebuild of the same on-disk state and compare this workspace.
        jdbc().update("DELETE FROM links");
        jdbc().update("DELETE FROM pages");
        reindex.reindex(ReindexOptions.full());

        Map<String, String> afterFull = latestBodies(ws);
        List<String> edgesAfterFull = resolvedEdges(ws);

        // The deleted page is a tombstone (empty latest body) after incremental; a full rebuild simply
        // never creates it. Compare only the live (non-empty) pages so the two strategies are
        // equivalent on what actually exists on disk.
        Map<String, String> liveAfterIncremental = afterIncremental.entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(liveAfterIncremental).isEqualTo(afterFull);
        assertThat(edgesAfterIncremental).isEqualTo(edgesAfterFull);
        // Sanity: the edited body won, the added page exists, the deleted one is not live.
        assertThat(afterFull).containsKey("concepts/added.md");
        assertThat(afterFull).doesNotContainKey("concepts/gone.md");
        assertThat(afterFull.get("concepts/edit.md")).isEqualTo("rewritten body with new words\n");
    }

    @Test
    void reIndexingUnchangedFilesIncrementallyIsANoOp() throws Exception {
        WorkspaceId ws = freshWorkspace();
        Identity p = pageAt(ws, "concepts/stable.md");
        seed(p, "Stable", "stable body\n");
        String headBefore = pages.readLatest(p).orElseThrow().id().value().toString();

        // Nothing changed on disk since HEAD; an incremental run touches nothing.
        ReindexReport report = reindex.reindex(ReindexOptions.incremental("HEAD"));
        assertThat(report.pagesIndexed()).isZero();
        assertThat(pages.readLatest(p).orElseThrow().id().value().toString()).isEqualTo(headBefore);
    }

    // --- re-embed is opt-in --------------------------------------------------------------------

    @Test
    void reEmbedRequestWithoutAMatchingEmbedderIsANoOpNotAFailure() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "concepts/e.md"), "E", "embed me maybe\n");

        // The real embed hook (#16 PageEmbeddingService) is wired in this context, but the 'test'
        // embedder is 8-dim while the page_embeddings column is 1024-dim, so embeddingsEnabled() is
        // false and the hook gracefully skips (DD-005). The reindex must still complete and index the
        // page — re-embed availability never fails the rebuild.
        ReindexReport report = reindex.reindex(ReindexOptions.full().withReEmbed(true));
        assertThat(report.pagesIndexed()).isGreaterThanOrEqualTo(1);
        Integer embeddings = jdbc().queryForObject("SELECT count(*) FROM page_embeddings", Integer.class);
        assertThat(embeddings).isZero();
    }

    @Test
    void reEmbedOffNeverWritesEmbeddingsEvenIfAnEmbedderWereActive() {
        WorkspaceId ws = freshWorkspace();
        seed(pageAt(ws, "concepts/noembed.md"), "N", "do not embed\n");

        // Default options (reEmbed=false): the embed hook is never invoked, full stop.
        reindex.reindex(ReindexOptions.full());
        Integer embeddings = jdbc().queryForObject("SELECT count(*) FROM page_embeddings", Integer.class);
        assertThat(embeddings).isZero();
    }

    // --- capture tables are primary, never rebuilt ---------------------------------------------

    @Test
    void fullReindexDoesNotTouchCaptureTables() {
        WorkspaceId ws = freshWorkspace();
        // Seeding a page creates the workspace/project rows; reuse their surrogate ids for capture.
        seed(pageAt(ws, "concepts/c.md"), "C", "a page\n");
        UUID workspaceId = jdbc().queryForObject(
                "SELECT id FROM workspaces WHERE slug = ?", UUID.class, ws.value());
        UUID projectId = jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = 'proj'", UUID.class, ws.value());

        // Insert a raw session + observation directly (simulating capture). Full reindex must leave
        // these PRIMARY rows intact (DD-002). All NOT-NULL columns and the canonical kind are supplied.
        UUID sessionId = com.agentmemory.core.Uuid7.randomUuid();
        UUID obsId = com.agentmemory.core.Uuid7.randomUuid();
        jdbc().update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, 'proj', now())",
                sessionId, workspaceId, projectId, ws.value());
        jdbc().update(
                "INSERT INTO observations "
                        + "(id, session_id, workspace_id, project_id, workspace, project, kind, "
                        + " payload, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'proj', 'user-prompt', 'remember this', now())",
                obsId, sessionId, workspaceId, projectId, ws.value());

        reindex.reindex(ReindexOptions.full());

        Integer obs = jdbc().queryForObject(
                "SELECT count(*) FROM observations WHERE id = ?", Integer.class, obsId);
        assertThat(obs).isEqualTo(1);
    }
}
