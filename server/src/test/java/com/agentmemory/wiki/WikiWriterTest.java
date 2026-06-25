package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.Page;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the writer that backs the {@code PageWriteCallback}: atomic file + commit + self-write mark. */
class WikiWriterTest {

    private WikiWriter newWriter(Path wiki, SelfWriteTracker tracker) {
        WikiPaths paths = new WikiPaths(wiki);
        WikiGit git = new WikiGit(wiki, "tester", "tester@localhost");
        return new WikiWriter(paths, new AtomicFileWriter(), tracker, git);
    }

    private static MarkdownDocument doc(String body) {
        PageFrontmatter fm = new PageFrontmatter(
                "Recall", PageKind.CONCEPT, false, null,
                WorkspaceId.of("acme"), ProjectId.of("agent-memory"),
                PagePath.of("concepts/recall.md"),
                Instant.parse("2026-06-25T12:00:00Z"), Instant.parse("2026-06-25T12:00:00Z"));
        return new MarkdownDocument(fm, body);
    }

    @Test
    void writesFileCommitsAndMarksSelfWrite(@TempDir Path wiki) throws Exception {
        SelfWriteTracker tracker = new SelfWriteTracker();
        WikiWriter writer = newWriter(wiki, tracker);
        MarkdownDocument document = doc("Fusion details.\n");

        var commit = writer.writeAndCommit(document, "consolidate: concepts/recall.md");

        Path file = wiki.resolve("acme/agent-memory/concepts/recall.md");
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.readString(file)).isEqualTo(document.render());
        assertThat(commit).isPresent();

        // The tracker now recognizes the on-disk content as a self-write (so the watcher skips it).
        String onDiskHash = AtomicFileWriter.hashOf(Files.readString(file));
        assertThat(tracker.isSelfWrite(file, onDiskHash)).isTrue();
    }

    @Test
    void identicalRewriteIsANoOpCommit(@TempDir Path wiki) throws Exception {
        SelfWriteTracker tracker = new SelfWriteTracker();
        WikiWriter writer = newWriter(wiki, tracker);
        MarkdownDocument document = doc("same body\n");

        assertThat(writer.writeAndCommit(document, "first")).isPresent();
        assertThat(writer.writeAndCommit(document, "second")).isEmpty(); // nothing changed on disk
    }

    @Test
    void toDocumentMapsPageRecordToFrontmatter() {
        Identity id = Identity.ofPage(
                WorkspaceId.of("ws"), ProjectId.of("p"), PagePath.of("decisions/storage.md"));
        Page page = new Page(PageId.newId(), id, "Storage decision", "body\n", true, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
        MarkdownDocument document =
                WikiWriter.toDocument(new PageRecord(page, MemoryLayer.SEMANTIC, 0, null));

        assertThat(document.frontmatter().kind()).isEqualTo(PageKind.DECISION);
        assertThat(document.frontmatter().title()).isEqualTo("Storage decision");
        assertThat(document.frontmatter().path()).isEqualTo(PagePath.of("decisions/storage.md"));
        assertThat(document.body()).isEqualTo("body\n");
    }
}
