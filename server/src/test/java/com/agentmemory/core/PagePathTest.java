package com.agentmemory.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.Path;

/**
 * Path-normalization rules (issue #3 acceptance criterion 3) — the {@link PagePath} / underlying
 * {@link PathNormalizer} contract. These cases are mirrored 1:1 by the Go test in
 * {@code client/internal/core} so both languages collapse the same spellings to the same key.
 */
class PagePathTest {

    @SuppressWarnings("unused")
    @TempDir
    Path unused; // keeps the JUnit TempDir extension on the classpath consistent with siblings

    @ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
    @CsvSource(
            delimiter = '|',
            value = {
                // separators: backslashes become forward slashes
                "concepts\\recall.md         | concepts/recall.md",
                // collapse duplicate slashes
                "concepts//recall.md         | concepts/recall.md",
                // strip a leading slash (paths are project-root-relative)
                "/concepts/recall.md         | concepts/recall.md",
                // strip a leading ./
                "./concepts/recall.md        | concepts/recall.md",
                // drop interior . segments
                "concepts/./recall.md        | concepts/recall.md",
                // ensure the .md extension is appended when missing
                "concepts/recall             | concepts/recall.md",
                // an existing extension is preserved, not doubled
                "concepts/recall.md          | concepts/recall.md",
                // case-insensitive: ASCII is lower-cased (path + extension)
                "Concepts/Recall.MD          | concepts/recall.md",
                // surrounding whitespace on segments is trimmed
                "  concepts / recall.md      | concepts/recall.md",
                // mixed separators + redundant segments together
                "\\a\\.\\b\\\\c              | a/b/c.md",
                // a root-level page keeps no leading slash
                "log.md                      | log.md"
            })
    void normalizesToCanonicalForm(String raw, String expected) {
        assertThat(PagePath.of(raw).value()).isEqualTo(expected);
    }

    @Test
    void normalizationIsIdempotent() {
        PagePath once = PagePath.of("Concepts\\\\Foo");
        PagePath twice = PagePath.of(once.value());
        assertThat(twice.value()).isEqualTo(once.value());
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void equalityFollowsNormalForm() {
        assertThat(PagePath.of("Concepts/Foo.md")).isEqualTo(PagePath.of("concepts/foo"));
    }

    @Test
    void rejectsParentTraversal() {
        assertThatThrownBy(() -> PagePath.of("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
    }

    @Test
    void rejectsInteriorTraversal() {
        assertThatThrownBy(() -> PagePath.of("concepts/../../secrets.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> PagePath.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPathThatNormalizesToEmpty() {
        assertThatThrownBy(() -> PagePath.of("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsNul() {
        assertThatThrownBy(() -> PagePath.of("a\0b.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NUL");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> PagePath.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesFileNameAndTopFolder() {
        PagePath path = PagePath.of("concepts/sub/recall.md");
        assertThat(path.fileName()).isEqualTo("recall.md");
        assertThat(path.topFolder()).isEqualTo("concepts");

        PagePath root = PagePath.of("log.md");
        assertThat(root.fileName()).isEqualTo("log.md");
        assertThat(root.topFolder()).isEmpty();
    }
}
