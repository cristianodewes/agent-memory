package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the crash-safe writer (invariant #10). True power-loss can't be simulated in a unit
 * test, but the observable guarantees of tmp+rename+fsync can: the target is only ever the old or the
 * complete new content (never partial), no temporary files are left behind, and the returned hash
 * matches the bytes written.
 */
class AtomicFileWriterTest {

    private final AtomicFileWriter writer = new AtomicFileWriter();

    @Test
    void writesContentAndReturnsMatchingHash(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("a/b/page.md");
        String content = "hello wiki\n";

        String hash = writer.write(target, content);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(content);
        assertThat(hash).isEqualTo(AtomicFileWriter.hashOf(content));
        assertThat(hash).hasSize(64); // sha-256 hex
    }

    @Test
    void overwriteReplacesAtomicallyAndLeavesNoTempFiles(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("page.md");
        writer.write(target, "version one\n");
        writer.write(target, "version two is longer\n");

        assertThat(Files.readString(target)).isEqualTo("version two is longer\n");
        assertThat(leftoverTempFiles(dir)).isEmpty();
    }

    @Test
    void repeatedWritesNeverLeaveAPartialOrTempFile(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("nested/dir/page.md");
        for (int i = 0; i < 25; i++) {
            String content = "iteration " + i + " ".repeat(i) + "\n";
            String hash = writer.write(target, content);
            // After each write the target is exactly the full content we just wrote — never partial.
            assertThat(Files.readString(target)).isEqualTo(content);
            assertThat(hash).isEqualTo(AtomicFileWriter.hashOf(content));
        }
        assertThat(leftoverTempFiles(dir.resolve("nested/dir"))).isEmpty();
    }

    @Test
    void createsParentDirectories(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("deeply/nested/new/page.md");
        writer.write(target, "x\n");
        assertThat(Files.exists(target)).isTrue();
    }

    private static List<Path> leftoverTempFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(".wiki-")
                    && p.getFileName().toString().endsWith(".tmp")).toList();
        }
    }
}
