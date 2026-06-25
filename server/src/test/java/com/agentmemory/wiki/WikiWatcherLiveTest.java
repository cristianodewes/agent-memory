package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 * End-to-end proof of the live {@link WikiFileWatcher} (issue #13 acceptance: "editing a page in
 * Obsidian and saving updates the Postgres index via the watcher"). Unlike
 * {@link WikiStoreIntegrationTest}, the real OS {@code WatchService} is left enabled and the test
 * waits for the watcher's own thread + debounce to pick up an external file write — no direct call
 * to {@code reconcile}. Also asserts the watcher does <em>not</em> re-ingest the app's own write.
 *
 * <p>WatchService latency varies by platform, so the assertions use Awaitility with a generous
 * timeout rather than a fixed sleep.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=true",
    "agent-memory.wiki.watch-debounce-millis=50"
})
@Testcontainers
class WikiWatcherLiveTest {

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

    private static Identity pageAt(String path) {
        return Identity.ofPage(
                WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", "")),
                ProjectId.of("proj"),
                PagePath.of(path));
    }

    @Test
    void liveWatcherReconcilesAnExternalEdit() throws Exception {
        // Seed a page through the app first so its workspace/project dirs exist and are watched.
        Identity id = pageAt("concepts/live.md");
        pages.create(id, "Seed", "seed body\n", wikiWriter.callbackFor("seed"));
        Path file = wikiPaths.resolve(id);

        // Now edit the file directly, as Obsidian/vim would.
        MarkdownDocument edited = new MarkdownDocument(
                new PageFrontmatter("Edited live", PageKind.CONCEPT, false, null,
                        id.workspace(), id.project(), id.page(),
                        Instant.parse("2026-06-25T12:00:00Z"), Instant.parse("2026-06-25T14:00:00Z")),
                "edited-by-human live body\n");
        Files.writeString(file, edited.render(), StandardCharsets.UTF_8);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            PageRecord latest = pages.readLatest(id).orElseThrow();
            assertThat(latest.page().body()).isEqualTo("edited-by-human live body\n");
            assertThat(latest.page().title()).isEqualTo("Edited live");
        });
    }

    @Test
    void liveWatcherDoesNotLoopOnTheAppsOwnWrite() throws Exception {
        Identity id = pageAt("concepts/noloop.md");
        PageRecord written = pages.create(id, "App", "app body\n", wikiWriter.callbackFor("app write"));

        // Give the watcher ample time to (wrongly) react; it must not create a second version.
        Thread.sleep(1500);

        PageRecord latest = pages.readLatest(id).orElseThrow();
        assertThat(latest.id()).isEqualTo(written.id());
        assertThat(latest.page().supersedes()).isNull();
    }
}
