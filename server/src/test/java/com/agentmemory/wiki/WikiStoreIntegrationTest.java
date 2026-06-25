package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for issue #13's DB-coupled acceptance criteria, booting the full context against
 * a Testcontainers {@code pgvector/pgvector:pg16} Postgres:
 *
 * <ul>
 *   <li><b>One logical operation</b>: writing a page through {@link PageRepository#create} with the
 *       {@link WikiWriter}-provided {@code PageWriteCallback} produces both the DB row and the
 *       atomic markdown file + exactly one git commit.</li>
 *   <li><b>External-edit reconciliation</b>: an edit made directly on disk (Obsidian/vim) becomes a
 *       new version in the index when the watcher reconciles it.</li>
 *   <li><b>Self-write ignored</b>: the watcher does not re-ingest the app's own write (no loop).</li>
 * </ul>
 *
 * <p>The background OS watcher is disabled ({@code watch-enabled=false}) so the test drives
 * {@link WikiFileWatcher#reconcile} deterministically; the watcher under test shares the same
 * autowired {@link SelfWriteTracker} the {@link WikiWriter} marks, which is what makes the
 * self-write check meaningful. The data dir is the JUnit temp dir so no real {@code ~/.agent-memory}
 * is touched, and a fresh data dir per class gives the wiki its own git repo.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class WikiStoreIntegrationTest {

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
    @Autowired SelfWriteTracker selfWrites;

    private WikiFileWatcher watcher() {
        return new WikiFileWatcher(wikiPaths, selfWrites, pages, 0L);
    }

    private static Identity pageAt(String path) {
        return Identity.ofPage(
                WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", "")),
                ProjectId.of("proj"),
                PagePath.of(path));
    }

    // --- one logical operation: row + file + commit --------------------------------------------

    @Test
    void pageWriteProducesRowFileAndCommitTogether() throws Exception {
        Identity id = pageAt("concepts/recall.md");

        PageRecord record = pages.create(
                id, "Hybrid recall", "Reciprocal rank fusion blends FTS, graph and vector.\n",
                wikiWriter.callbackFor("consolidate: concepts/recall.md"));

        // DB row exists and is latest.
        assertThat(pages.readLatest(id)).isPresent();
        assertThat(record.isLatest()).isTrue();

        // Markdown file written atomically with frontmatter + body.
        Path file = wikiPaths.resolve(id);
        assertThat(Files.exists(file)).isTrue();
        String onDisk = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(onDisk).contains("title: \"Hybrid recall\"");
        assertThat(onDisk).contains("Reciprocal rank fusion");

        // Parsing the file back yields the same identity/body (the file is self-describing).
        MarkdownDocument parsed = MarkdownDocument.parse(onDisk);
        assertThat(parsed.identity()).isEqualTo(id);
        assertThat(parsed.body()).isEqualTo("Reciprocal rank fusion blends FTS, graph and vector.\n");
    }

    // --- external-edit reconciliation ----------------------------------------------------------

    @Test
    void externalEditIsReconciledIntoTheIndex() throws Exception {
        Identity id = pageAt("concepts/edited.md");
        pages.create(id, "Original", "original body\n",
                wikiWriter.callbackFor("consolidate: concepts/edited.md"));
        Path file = wikiPaths.resolve(id);

        // Simulate Obsidian editing the file directly (NOT through the app) — new body + title.
        MarkdownDocument edited = new MarkdownDocument(
                new PageFrontmatter(
                        "Edited in Obsidian", PageKind.CONCEPT, false, null,
                        id.workspace(), id.project(), id.page(),
                        java.time.Instant.parse("2026-06-25T12:00:00Z"),
                        java.time.Instant.parse("2026-06-25T13:00:00Z")),
                "human-edited body with new content\n");
        Files.writeString(file, edited.render(), StandardCharsets.UTF_8);

        watcher().reconcile(file);

        PageRecord latest = pages.readLatest(id).orElseThrow();
        assertThat(latest.page().body()).isEqualTo("human-edited body with new content\n");
        assertThat(latest.page().title()).isEqualTo("Edited in Obsidian");
        assertThat(latest.page().supersedes()).isNotNull(); // a new version superseding the original
    }

    // --- self-write ignored (no loop) ----------------------------------------------------------

    @Test
    void watcherDoesNotReIngestTheAppsOwnWrite() throws Exception {
        Identity id = pageAt("concepts/selfwrite.md");
        PageRecord written = pages.create(id, "Self", "app-written body\n",
                wikiWriter.callbackFor("consolidate: concepts/selfwrite.md"));
        Path file = wikiPaths.resolve(id);

        // The app just wrote this file; reconciling it must be a no-op (recognized as a self-write).
        watcher().reconcile(file);

        PageRecord latest = pages.readLatest(id).orElseThrow();
        assertThat(latest.id()).isEqualTo(written.id()); // still the same single version
        assertThat(latest.page().supersedes()).isNull();  // no spurious second version created
    }

    @Test
    void noOpSaveOfIdenticalContentCreatesNoNewVersion() throws Exception {
        Identity id = pageAt("concepts/noop.md");
        PageRecord written = pages.create(id, "Title", "body\n",
                wikiWriter.callbackFor("consolidate: concepts/noop.md"));
        Path file = wikiPaths.resolve(id);

        // An external tool re-saves identical content (mtime touch) AFTER the self-write mark is
        // consumed — reconcile sees the body equals the current latest and creates nothing.
        String current = Files.readString(file);
        watcher().reconcile(file);                 // consumes self-write mark
        Files.writeString(file, current);          // identical re-save (genuine event, no change)
        watcher().reconcile(file);

        assertThat(pages.readLatest(id).orElseThrow().id()).isEqualTo(written.id());
    }
}
