package com.agentmemory.wiki;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles <em>external</em> edits to the {@code wiki/} tree (Obsidian, vim, {@code git pull})
 * into the Postgres index, so the markdown source of truth (DD-002) and the derived index stay
 * consistent even when a human edits a file directly (issue #13 acceptance).
 *
 * <p><strong>Polling, not OS events.</strong> It periodically walks the wiki tree and reconciles any
 * {@code .md} file whose content hash changed since the last scan. Polling is deliberate: the JDK
 * {@code WatchService} is unreliable across platforms (notably multi-second latency and missed
 * events on Windows, and races registering freshly-created subdirectories), whereas a scan never
 * misses a change and is trivial to reason about. The wiki is not a hot path — human-paced edits
 * tolerate a sub-second poll interval — so the simplicity/robustness trade is worth it.
 *
 * <p><strong>No feedback loop (invariant #10).</strong> The app's own writes go through
 * {@link WikiWriter}, which records the written content hash in {@link SelfWriteTracker} first. A
 * scan that sees a file whose hash matches the recorded expectation treats it as a self-write and
 * skips it. Genuine external content is reconciled via {@link PageRepository#create} with a
 * {@code null} callback — index only, <em>no</em> file write — so reconciliation can never trigger
 * another wiki write and loop.
 *
 * <p>The scan runs on a single daemon thread; {@link #start()}/{@link #stop()} manage its lifecycle.
 * {@link #reconcile(Path)} is package-visible-for-tests so the per-file logic can be exercised
 * deterministically without waiting for a poll tick.
 */
public final class WikiFileWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WikiFileWatcher.class);

    private final WikiPaths paths;
    private final SelfWriteTracker selfWrites;
    private final PageRepository pages;
    private final long pollMillis;

    private final AtomicBoolean running = new AtomicBoolean(false);
    // Last content hash we reconciled per file, so a scan only acts on genuine changes.
    private final Map<Path, String> lastSeenHash = new ConcurrentHashMap<>();
    // Files whose latest content failed to parse. We WARN once on the valid/new -> malformed
    // transition, then demote repeats of the SAME still-malformed file to DEBUG, so a file rewritten
    // every poll does not spam the WARN (issue #118). An entry is cleared when the file parses again
    // or stops being a regular file (removed/renamed), so a later regression WARNs once again.
    private final Set<Path> malformedPaths = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService poller;

    public WikiFileWatcher(WikiPaths paths, SelfWriteTracker selfWrites,
                           PageRepository pages, long pollMillis) {
        this.paths = paths;
        this.selfWrites = selfWrites;
        this.pages = pages;
        this.pollMillis = Math.max(1, pollMillis);
    }

    /** Start polling (idempotent). Seeds the baseline from the current tree, then scans on a timer. */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // Baseline the current on-disk state so we only react to edits made after startup; the index
        // is reconstructed from the wiki by reindex (#14), which is out of scope here.
        seedBaseline();
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wiki-watch-poll");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::scanQuietly, pollMillis, pollMillis, TimeUnit.MILLISECONDS);
        log.info("wiki watcher polling {} every {}ms", paths.wikiDir(), pollMillis);
    }

    /** Stop polling and release the scheduler (idempotent). */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (poller != null) {
            poller.shutdownNow();
        }
        log.info("wiki watcher stopped");
    }

    private void seedBaseline() {
        forEachMarkdownFile(file -> {
            String hash = hashOfFile(file);
            if (hash != null) {
                lastSeenHash.put(file, hash);
            }
        });
    }

    private void scanQuietly() {
        try {
            forEachMarkdownFile(this::onScanned);
        } catch (RuntimeException e) {
            log.warn("wiki scan failed: {}", e.toString());
        }
    }

    private void onScanned(Path file) {
        String hash = hashOfFile(file);
        if (hash == null) {
            return;
        }
        String previous = lastSeenHash.get(file);
        if (hash.equals(previous)) {
            return; // unchanged since last scan
        }
        // Content changed (or new file). reconcile() decides self-write vs external edit.
        reconcile(file);
        lastSeenHash.put(file, hash);
    }

    /**
     * Reconcile one wiki file into the index. Skips self-writes (via {@link SelfWriteTracker}) and
     * no-op edits (content equal to the current latest). A genuine external edit becomes a new page
     * version through {@link PageRepository#create} with a {@code null} callback (index only).
     *
     * @param file the absolute path of a {@code .md} file under the wiki root.
     */
    void reconcile(Path file) {
        if (!Files.isRegularFile(file)) {
            selfWrites.forget(file);
            lastSeenHash.remove(file);
            malformedPaths.remove(file); // removed/renamed: reset the throttle
            return; // deletes/renames: handled by reindex (#14), out of scope here
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("could not read changed wiki file {}: {}", file, e.toString());
            return;
        }
        String hash = AtomicFileWriter.hashOf(content);
        if (selfWrites.isSelfWrite(file, hash)) {
            malformedPaths.remove(file); // app rewrote valid content: reset the throttle
            log.debug("ignoring self-write {}", file);
            return;
        }

        String[] components = paths.componentsOf(file);
        if (components == null) {
            return; // stray file not at wiki/<ws>/<project>/<path> depth
        }
        MarkdownDocument doc;
        try {
            doc = MarkdownDocument.parse(content);
        } catch (WikiFormatException e) {
            if (malformedPaths.add(file)) {
                // First time this file is seen malformed (valid/new -> malformed transition): WARN
                // once, keeping path + reason so the message stays actionable.
                log.warn("skipping malformed wiki file {}: {}", file, e.getMessage());
            } else {
                // Same file still malformed on a later scan (e.g. rewritten every poll): demote to
                // DEBUG so a file that changes each tick does not re-emit the WARN (issue #118).
                log.debug("still skipping malformed wiki file {}: {}", file, e.getMessage());
            }
            return;
        }
        // Parsed cleanly: if this path was previously malformed the regression is over, so clear the
        // throttle — a future malformed edit will WARN once again.
        malformedPaths.remove(file);

        // Trust the file's location for identity (a moved file is keyed by where it now lives).
        Identity identity = Identity.ofPage(
                WorkspaceId.of(components[0]),
                ProjectId.of(components[1]),
                PagePath.of(components[2]));

        Optional<PageRecord> current = pages.readLatest(identity);
        if (current.isPresent()
                && current.get().page().body().equals(doc.body())
                && current.get().page().title().equals(doc.frontmatter().title())) {
            return; // nothing materially changed; do not create a spurious version
        }

        pages.create(identity, doc.frontmatter().title(), doc.body(), null);
        log.info("reconciled external edit into index: {}", identity.page().value());
    }

    private void forEachMarkdownFile(java.util.function.Consumer<Path> action) {
        Path root = paths.wikiDir();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !isUnderGitDir(p))
                    .forEach(action);
        } catch (IOException e) {
            log.warn("could not walk wiki tree {}: {}", root, e.toString());
        }
    }

    private static String hashOfFile(Path file) {
        try {
            return AtomicFileWriter.hashOf(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null; // file vanished between walk and read; next scan will catch up
        }
    }

    private static boolean isUnderGitDir(Path p) {
        for (Path part : p) {
            if (part.toString().equals(".git")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        stop();
    }
}
