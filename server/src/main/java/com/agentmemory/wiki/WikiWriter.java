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
     * Map a persisted {@link PageRecord} to its {@link MarkdownDocument}. {@code pinned}/{@code
     * slotKind} default (false/null) here — they are set by the curation flows that own them (#24
     * pinning, slot pages); #13 establishes the contract and round-trips them.
     *
     * @param record the stored page.
     * @return the document to write to disk.
     */
    public static MarkdownDocument toDocument(PageRecord record) {
        var page = record.page();
        PageFrontmatter fm = new PageFrontmatter(
                page.title(),
                PageKind.fromPath(page.path()),
                false,
                null,
                page.identity().workspace(),
                page.identity().project(),
                page.path(),
                page.createdAt(),
                page.updatedAt());
        return new MarkdownDocument(fm, page.body());
    }
}
