package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentmemory.core.Identity;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.store.PageWriteCallback;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Scan-path coverage for {@link WikiFileWatcher} (issue #148): the walker must exclude
 * {@link SessionLog#LOG_FILE_NAME} — the frontmatter-less event ledger the app itself appends inside
 * the wiki tree — so the watcher stops warning about (and re-hashing) a machine file that is not a
 * page, mirroring {@code DefaultReindexService.isPageFile}. Runs the real poller over a
 * {@code @TempDir} with a fake {@link PageRepository}; no Postgres/docker.
 */
class WikiFileWatcherScanTest {

    @TempDir
    Path wikiDir;

    private RecordingPages pages;
    private WikiFileWatcher watcher;

    private Logger watcherLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        pages = new RecordingPages();
        watcher = new WikiFileWatcher(new WikiPaths(wikiDir), new SelfWriteTracker(), pages, 50);

        watcherLogger = (Logger) LoggerFactory.getLogger(WikiFileWatcher.class);
        originalLevel = watcherLogger.getLevel();
        watcherLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        watcherLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        watcher.close();
        watcherLogger.detachAppender(appender);
        appender.stop();
        watcherLogger.setLevel(originalLevel);
    }

    @Test
    void theSessionLogLedgerIsInvisibleToTheScanWhileRealPagesReconcile() throws Exception {
        Path projectDir = wikiDir.resolve("ws").resolve("proj");
        Files.createDirectories(projectDir);
        watcher.start();

        // The app appends the frontmatter-less ledger (SessionLog's exact line shape) while a genuine
        // external page edit lands next to it — the production layout from the bug report.
        Files.writeString(projectDir.resolve(SessionLog.LOG_FILE_NAME),
                "## [2026-07-03T12:41:19Z] SessionStart\n## [2026-07-03T12:42:14Z] PreToolUse | tool: Glob\n",
                StandardCharsets.UTF_8);
        // Atomic write: the live poller (50ms) must never observe a half-written page, or a spurious
        // "malformed wiki file" WARN would flake the noneMatch assertion below.
        writeAtomic(projectDir.resolve("notes.md"), validPage("Notes", "notes.md", "external edit\n"));

        // The real page is reconciled into the index…
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(pages.createdTitles).containsExactly("Notes"));

        // …and the ledger never produced a malformed-page warning nor an index entry: the scan simply
        // does not see it (before issue #148 every append re-triggered "skipping malformed wiki file").
        assertThat(appender.list)
                .noneMatch(e -> e.getFormattedMessage().contains("malformed wiki file"));
        assertThat(pages.createdTitles).hasSize(1);

        // Keep appending (the ledger grows on every ingested observation in production): still silent.
        Files.writeString(projectDir.resolve(SessionLog.LOG_FILE_NAME),
                "## [2026-07-03T12:43:02Z] PostToolUse | tool: Read\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        Thread.sleep(300); // several poll ticks at 50ms
        assertThat(appender.list)
                .noneMatch(e -> e.getFormattedMessage().contains("malformed wiki file"));
        assertThat(pages.createdTitles).hasSize(1);
    }

    /** Write via a temp sibling + atomic move so the live poller never sees a half-written page. */
    private static void writeAtomic(Path target, String content) throws Exception {
        Path tmp = Files.createTempFile(target.getParent(), ".test-", ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** A well-formed page file (frontmatter + body) that {@link MarkdownDocument#parse} accepts. */
    private static String validPage(String title, String path, String body) {
        PageFrontmatter fm = new PageFrontmatter(
                title, PageKind.CONCEPT, false, null,
                WorkspaceId.of("ws"), ProjectId.of("proj"), PagePath.of(path),
                Instant.parse("2026-06-25T12:00:00Z"), Instant.parse("2026-06-25T14:00:00Z"));
        return new MarkdownDocument(fm, body).render();
    }

    /** Minimal in-memory {@link PageRepository}: only the reconcile path's methods are live. */
    private static final class RecordingPages implements PageRepository {
        final List<String> createdTitles = new CopyOnWriteArrayList<>();

        @Override
        public PageRecord create(Identity identity, String title, String body) {
            throw new UnsupportedOperationException("unused on the reconcile path");
        }

        @Override
        public PageRecord create(Identity identity, String title, String body, PageWriteCallback cb) {
            createdTitles.add(title);
            return null; // reconcile ignores the returned record
        }

        @Override
        public Optional<PageRecord> readLatest(Identity identity) {
            return Optional.empty();
        }

        @Override
        public List<PageRecord> listLatest(WorkspaceId workspace, ProjectId project) {
            throw new UnsupportedOperationException("unused on the reconcile path");
        }

        @Override
        public Optional<PageRecord> findById(PageId id) {
            throw new UnsupportedOperationException("unused on the reconcile path");
        }

        @Override
        public Optional<PageRecord> reinforce(PageId id) {
            throw new UnsupportedOperationException("unused on the reconcile path");
        }

        @Override
        public int dropWorkingFromLatest(WorkspaceId workspace, ProjectId project) {
            throw new UnsupportedOperationException("unused on the reconcile path");
        }
    }
}
