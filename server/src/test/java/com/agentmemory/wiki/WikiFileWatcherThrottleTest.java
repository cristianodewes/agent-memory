package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Unit coverage for the per-file malformed-page log throttle (issue #118). Drives
 * {@link WikiFileWatcher#reconcile(Path)} directly — the documented test seam — with a fake
 * {@link PageRepository} and a real {@link WikiPaths}/{@link SelfWriteTracker} over a {@code @TempDir},
 * so it needs no Postgres/docker. Log levels are asserted with a Logback {@link ListAppender} attached
 * to the watcher's own logger.
 *
 * <p>The mechanic under test: a malformed file (no {@code ---} frontmatter) WARNs once on the
 * valid/new -> malformed transition, then DEBUGs while it stays malformed; the throttle resets when
 * the file parses again or is removed, so a later regression WARNs once more.
 */
class WikiFileWatcherThrottleTest {

    @TempDir
    Path wikiDir;

    private RecordingPages pages;
    private WikiFileWatcher watcher;
    private Path logFile; // wiki/ws/proj/log.md — the file from the bug report

    private Logger watcherLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws Exception {
        pages = new RecordingPages();
        watcher = new WikiFileWatcher(new WikiPaths(wikiDir), new SelfWriteTracker(), pages, 250);
        logFile = wikiDir.resolve("ws").resolve("proj").resolve("log.md");
        Files.createDirectories(logFile.getParent());

        watcherLogger = (Logger) LoggerFactory.getLogger(WikiFileWatcher.class);
        originalLevel = watcherLogger.getLevel();
        watcherLogger.setLevel(Level.DEBUG); // ensure DEBUG events reach the appender
        appender = new ListAppender<>();
        appender.start();
        watcherLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        watcherLogger.detachAppender(appender);
        appender.stop();
        watcherLogger.setLevel(originalLevel);
    }

    @Test
    void malformedFileRewrittenManyTimesWarnsOnceThenDebugs() throws Exception {
        int rewrites = 4;
        for (int i = 0; i < rewrites; i++) {
            // Distinct content each time models a file rewritten on every poll (hash changes), which is
            // exactly the burst the issue describes — yet it must WARN only once.
            writeMalformed("garbage revision " + i + " with no frontmatter\n");
            watcher.reconcile(logFile);
        }

        assertThat(countMalformed(Level.WARN)).isEqualTo(1);
        assertThat(countMalformed(Level.DEBUG)).isEqualTo(rewrites - 1);
        assertThat(pages.createdTitles).isEmpty(); // a malformed file never enters the index

        // The single WARN keeps path + reason, so it stays actionable.
        String warn = firstMalformedMessage(Level.WARN);
        assertThat(warn).contains(logFile.toString());
        assertThat(warn).contains("page must start with a '---' frontmatter fence");
    }

    @Test
    void recoveryToValidContentResetsThrottleSoRegressionWarnsAgain() throws Exception {
        // 1) malformed -> WARN once.
        writeMalformed("not a page\n");
        watcher.reconcile(logFile);
        assertThat(countMalformed(Level.WARN)).isEqualTo(1);

        // 2) the file is fixed (parses) -> reconciled into the index, no malformed log emitted.
        Files.writeString(logFile, validPage("Fixed", "real body\n"), StandardCharsets.UTF_8);
        watcher.reconcile(logFile);
        assertThat(pages.createdTitles).containsExactly("Fixed");

        // 3) it regresses to malformed again -> WARNs once more (throttle was reset), not DEBUG.
        writeMalformed("broken again\n");
        watcher.reconcile(logFile);

        assertThat(countMalformed(Level.WARN)).isEqualTo(2);
        assertThat(countMalformed(Level.DEBUG)).isZero();
    }

    @Test
    void removalClearsPerFileStateSoRegressionWarnsAgain() throws Exception {
        // malformed -> WARN once.
        writeMalformed("junk\n");
        watcher.reconcile(logFile);
        assertThat(countMalformed(Level.WARN)).isEqualTo(1);

        // removed/renamed -> reconcile's non-regular branch clears the per-file state (no log).
        Files.delete(logFile);
        watcher.reconcile(logFile);

        // re-created malformed at the same path -> a fresh transition, so WARN again (not DEBUG).
        writeMalformed("junk once more\n");
        watcher.reconcile(logFile);

        assertThat(countMalformed(Level.WARN)).isEqualTo(2);
        assertThat(countMalformed(Level.DEBUG)).isZero();
    }

    @Test
    void validExternalEditIsReconciledWithoutAnyMalformedLog() throws Exception {
        Files.writeString(logFile, validPage("Concept", "concept body\n"), StandardCharsets.UTF_8);
        watcher.reconcile(logFile);

        assertThat(pages.createdTitles).containsExactly("Concept");
        assertThat(pages.lastBody).contains("concept body");
        assertThat(countMalformed(Level.WARN)).isZero();
        assertThat(countMalformed(Level.DEBUG)).isZero();
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void writeMalformed(String content) throws Exception {
        Files.writeString(logFile, content, StandardCharsets.UTF_8); // no leading '---' fence
    }

    /** A well-formed page file (frontmatter + body) that {@link MarkdownDocument#parse} accepts. */
    private static String validPage(String title, String body) {
        PageFrontmatter fm = new PageFrontmatter(
                title, PageKind.CONCEPT, false, null,
                WorkspaceId.of("ws"), ProjectId.of("proj"), PagePath.of("log.md"),
                Instant.parse("2026-06-25T12:00:00Z"), Instant.parse("2026-06-25T14:00:00Z"));
        return new MarkdownDocument(fm, body).render();
    }

    /** Count log events for the malformed-page message at {@code level} (ignores other log lines). */
    private long countMalformed(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .filter(e -> e.getFormattedMessage().contains("malformed wiki file"))
                .count();
    }

    private String firstMalformedMessage(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .filter(e -> e.getFormattedMessage().contains("malformed wiki file"))
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Minimal in-memory {@link PageRepository}: {@link #reconcile} only calls {@link #readLatest}
     * (kept empty so every valid edit is treated as a new version) and the 4-arg {@link #create}
     * (recorded). The rest are unused on this path and fail loudly if reached.
     */
    private static final class RecordingPages implements PageRepository {
        final List<String> createdTitles = new ArrayList<>();
        String lastBody;

        @Override
        public PageRecord create(Identity identity, String title, String body) {
            throw new UnsupportedOperationException("unused on the reconcile path");
        }

        @Override
        public PageRecord create(Identity identity, String title, String body, PageWriteCallback cb) {
            createdTitles.add(title);
            lastBody = body;
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
