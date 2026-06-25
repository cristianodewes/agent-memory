package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Round-trip + edge-case tests for the page file format (frontmatter contract + body). */
class MarkdownDocumentTest {

    private static MarkdownDocument sample(String body) {
        PageFrontmatter fm = new PageFrontmatter(
                "Hybrid recall",
                PageKind.CONCEPT,
                false,
                null,
                WorkspaceId.of("acme"),
                ProjectId.of("agent-memory"),
                PagePath.of("concepts/recall.md"),
                Instant.parse("2026-06-25T12:00:00Z"),
                Instant.parse("2026-06-25T12:30:00Z"));
        return new MarkdownDocument(fm, body);
    }

    @Test
    void rendersFenceFrontmatterAndBody() {
        String text = sample("Body line one.\n").render();
        assertThat(text).startsWith("---\n");
        assertThat(text).contains("title: \"Hybrid recall\"\n");
        assertThat(text).contains("kind: \"concepts\"\n");
        assertThat(text).contains("workspace: \"acme\"\n");
        assertThat(text).contains("path: \"concepts/recall.md\"\n");
        assertThat(text).contains("\n---\n\nBody line one.\n");
    }

    @Test
    void roundTripsThroughParse() {
        MarkdownDocument original = sample("Reciprocal rank fusion blends FTS, graph and vector.\n");
        MarkdownDocument parsed = MarkdownDocument.parse(original.render());

        assertThat(parsed.frontmatter().title()).isEqualTo("Hybrid recall");
        assertThat(parsed.frontmatter().kind()).isEqualTo(PageKind.CONCEPT);
        assertThat(parsed.frontmatter().pinned()).isFalse();
        assertThat(parsed.frontmatter().workspace()).isEqualTo(WorkspaceId.of("acme"));
        assertThat(parsed.frontmatter().path()).isEqualTo(PagePath.of("concepts/recall.md"));
        assertThat(parsed.frontmatter().createdAt()).isEqualTo(Instant.parse("2026-06-25T12:00:00Z"));
        assertThat(parsed.body()).isEqualTo("Reciprocal rank fusion blends FTS, graph and vector.\n");
        assertThat(parsed.identity()).isEqualTo(original.identity());
    }

    @Test
    void toleratesCrlfFromWindowsEditors() {
        String crlf = sample("Edited in Notepad.\n").render().replace("\n", "\r\n");
        MarkdownDocument parsed = MarkdownDocument.parse(crlf);
        assertThat(parsed.body()).isEqualTo("Edited in Notepad.\n");
        assertThat(parsed.frontmatter().title()).isEqualTo("Hybrid recall");
    }

    @Test
    void preservesEmbeddedQuotesAndBackslashesInValues() {
        PageFrontmatter fm = new PageFrontmatter(
                "Title with \"quotes\" and \\ slash",
                PageKind.DECISION,
                true,
                "rule",
                WorkspaceId.of("ws"),
                ProjectId.of("p"),
                PagePath.of("decisions/x.md"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
        MarkdownDocument parsed = MarkdownDocument.parse(new MarkdownDocument(fm, "b\n").render());
        assertThat(parsed.frontmatter().title()).isEqualTo("Title with \"quotes\" and \\ slash");
        assertThat(parsed.frontmatter().pinned()).isTrue();
        assertThat(parsed.frontmatter().slotKind()).isEqualTo("rule");
    }

    @Test
    void rejectsMissingFrontmatterFence() {
        assertThatThrownBy(() -> MarkdownDocument.parse("no frontmatter here\n"))
                .isInstanceOf(WikiFormatException.class);
    }

    @Test
    void rejectsMissingRequiredKey() {
        String text = "---\ntitle: \"x\"\nkind: \"concepts\"\n---\n\nbody\n";
        assertThatThrownBy(() -> MarkdownDocument.parse(text))
                .isInstanceOf(WikiFormatException.class)
                .hasMessageContaining("workspace");
    }
}
