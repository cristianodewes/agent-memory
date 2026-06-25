package com.agentmemory.wiki;

import com.agentmemory.core.Identity;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageWriteCallback;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The high-level wiki write API (issue #13): renders a page to its markdown file, writes it
 * atomically (invariant #10), and commits it to git (commit-on-write). It is the concrete side
 * effect behind {@link PageWriteCallback} — the seam #12 ({@code store}) exposed so the DB row and
 * the wiki file are written in one logical operation (DD-002: the file body is authoritative, the
 * row is the derived index).
 *
 * <p>Ordering within a write: record the expected content hash (so the watcher recognizes this as a
 * self-write and does not loop), then atomic-write the file, then stage + commit. The hash is
 * recorded from the rendered text up front; {@link AtomicFileWriter} returns the same hash from the
 * bytes it wrote, and the two are asserted equal as a guard.
 */
public final class WikiWriter {

    private static final Logger log = LoggerFactory.getLogger(WikiWriter.class);

    private final WikiPaths paths;
    private final AtomicFileWriter fileWriter;
    private final SelfWriteTracker selfWrites;
    private final WikiGit git;

    public WikiWriter(WikiPaths paths, AtomicFileWriter fileWriter,
                      SelfWriteTracker selfWrites, WikiGit git) {
        this.paths = paths;
        this.fileWriter = fileWriter;
        this.selfWrites = selfWrites;
        this.git = git;
    }

    /**
     * Render, atomically write, and commit a page document. Returns the created commit (empty if the
     * content was byte-identical to what is already on disk and tracked — a no-op write).
     *
     * @param document      the full page (frontmatter + body).
     * @param commitMessage the git commit message.
     * @return the commit created, or empty when nothing changed.
     * @throws IOException if the atomic file write fails.
     */
    public Optional<RevCommit> writeAndCommit(MarkdownDocument document, String commitMessage)
            throws IOException {
        Identity identity = document.identity();
        Path file = paths.resolve(identity);
        String rendered = document.render();

        // Record BEFORE writing so the watcher can never observe the write before the expectation.
        String expectedHash = AtomicFileWriter.hashOf(rendered);
        selfWrites.recordWrite(file, expectedHash);

        String writtenHash = fileWriter.write(file, rendered);
        if (!writtenHash.equals(expectedHash)) {
            // Should be impossible (same bytes); guard against an encoding mismatch corrupting state.
            selfWrites.recordWrite(file, writtenHash);
            throw new IllegalStateException("written hash differs from rendered hash for " + file);
        }

        Optional<RevCommit> commit = git.stageAndCommit(commitMessage, List.of(file));
        log.debug("wiki write {} -> {}", identity.page().value(),
                commit.map(c -> c.getName()).orElse("(no change)"));
        return commit;
    }

    /**
     * Render, atomically write, and commit <em>several</em> page documents as a single git commit
     * (issue #19 atomic multi-page fan-out). Every file is written to disk first, then all are staged
     * and committed together via {@link WikiGit#stageAndCommit(String, Iterable)} so the fan-out lands
     * as one commit (all-or-nothing at the git layer). Intended to be called from inside the store's
     * write transaction (after the page rows are inserted) so the rows, the files, and the one commit
     * are one logical operation; a failure here throws and rolls the rows back.
     *
     * <p><strong>File cleanup on failure.</strong> If a later file write (or the commit) throws after
     * earlier files were already written, the files written by <em>this</em> call are best-effort
     * removed before the exception propagates, so a rolled-back transaction does not leave orphan files
     * on disk that a later reindex would resurrect. (Pre-existing versions are restored by the wiki's
     * git history / a reindex; this method only cleans up what it just wrote.)
     *
     * @param documents     the pages to write; never null. Empty ⇒ no-op, returns empty.
     * @param commitMessage the single git commit message for the whole fan-out.
     * @return the created commit, or empty when nothing changed (all byte-identical) or no documents.
     * @throws IOException if an atomic file write fails (after cleaning up files already written).
     */
    public Optional<RevCommit> writeAllAndCommit(List<MarkdownDocument> documents, String commitMessage)
            throws IOException {
        if (documents == null || documents.isEmpty()) {
            return Optional.empty();
        }
        List<Path> written = new java.util.ArrayList<>(documents.size());
        try {
            for (MarkdownDocument document : documents) {
                Path file = paths.resolve(document.identity());
                String rendered = document.render();
                // Record the expectation BEFORE writing so the watcher recognizes the self-write.
                String expectedHash = AtomicFileWriter.hashOf(rendered);
                selfWrites.recordWrite(file, expectedHash);
                String writtenHash = fileWriter.write(file, rendered);
                if (!writtenHash.equals(expectedHash)) {
                    selfWrites.recordWrite(file, writtenHash);
                    throw new IllegalStateException(
                            "written hash differs from rendered hash for " + file);
                }
                written.add(file);
            }
            // One commit for the whole fan-out (all staged files, single revision).
            Optional<RevCommit> commit = git.stageAndCommit(commitMessage, written);
            log.debug("wiki multi-write {} pages -> {}", documents.size(),
                    commit.map(RevCommit::getName).orElse("(no change)"));
            return commit;
        } catch (IOException | RuntimeException e) {
            // Roll back the files this call wrote so the rolled-back DB transaction and the wiki do not
            // drift (best effort; a reindex reconciles anything left behind from a partial git state).
            for (Path f : written) {
                try {
                    selfWrites.forget(f);
                    java.nio.file.Files.deleteIfExists(f);
                } catch (IOException cleanup) {
                    log.warn("failed to clean up partial multi-write file {}: {}", f, cleanup.toString());
                }
            }
            throw e;
        }
    }

    /**
     * Delete a page's markdown file from the wiki and commit the removal (issue #20
     * {@code memory_delete_page}). The file is removed from disk and the deletion staged + committed,
     * so the wiki git history (the source of truth, DD-002) records the page being removed and a later
     * reindex (#14) will not resurrect it. Any pending self-write expectation for the path is cleared
     * via {@link SelfWriteTracker#forget(Path)}; the watcher already treats a vanished file as a
     * drop-from-tracking (it reconciles by content hash on existing files), so the removal does not
     * loop back into the index.
     *
     * <p>Idempotent: if the file is already absent (e.g. a duplicate delete), it is a no-op that
     * stages/commits nothing and returns empty — deleting a missing page is a success.
     *
     * @param identity      the page-scoped identity whose file to remove.
     * @param commitMessage the git commit message for the removal.
     * @return the commit created, or empty when there was nothing on disk to remove.
     * @throws IOException if removing the file fails.
     */
    public Optional<RevCommit> deleteAndCommit(Identity identity, String commitMessage)
            throws IOException {
        Path file = paths.resolve(identity);
        selfWrites.forget(file);
        if (!java.nio.file.Files.exists(file)) {
            return Optional.empty(); // nothing on disk: clean no-op (idempotent delete)
        }
        java.nio.file.Files.delete(file);
        Optional<RevCommit> commit = git.stageAndCommit(commitMessage, List.of(file));
        log.debug("wiki delete {} -> {}", identity.page().value(),
                commit.map(RevCommit::getName).orElse("(no change)"));
        return commit;
    }

    /**
     * Build a {@link PageWriteCallback} that persists the given page to the wiki within the store's
     * write transaction. The body is the page's markdown; the frontmatter is derived from the page's
     * identity, title and timestamps (kind from the path). Intended to be passed to
     * {@code PageRepository.create(identity, title, body, callback)} so the row and file commit
     * together; a write failure throws and rolls the DB row back.
     *
     * @param commitMessage the commit message for this write (e.g. "consolidate: concepts/recall.md").
     * @return a callback that writes + commits the persisted page.
     */
    public PageWriteCallback callbackFor(String commitMessage) {
        return persisted -> writeAndCommit(toDocument(persisted), commitMessage);
    }

    /**
     * Map a persisted {@link PageRecord} to its {@link MarkdownDocument}. A {@code _slots/} page is
     * <strong>auto-pinned</strong> (issue #26): its {@code pinned} frontmatter is forced on via
     * {@link Slots#normalizePinned} so a slot is always written exempt from the retention sweep,
     * regardless of how it was created. {@code slotKind} defaults to {@code null} here (resolved to
     * {@code state} when read) — the write tool (#20) / consolidation set an explicit kind; #13
     * establishes the round-trip and pinning is enforced here.
     *
     * @param record the stored page.
     * @return the document to write to disk.
     */
    public static MarkdownDocument toDocument(PageRecord record) {
        var page = record.page();
        PageFrontmatter fm = new PageFrontmatter(
                page.title(),
                PageKind.fromPath(page.path()),
                Slots.normalizePinned(page.path(), false),
                null,
                page.identity().workspace(),
                page.identity().project(),
                page.path(),
                page.createdAt(),
                page.updatedAt());
        return new MarkdownDocument(fm, page.body());
    }
}
