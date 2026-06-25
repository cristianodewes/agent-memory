package com.agentmemory.reindex;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WikilinkParser} — the {@code [[...]]} extraction reindex (#14) uses to rebuild
 * the {@code links} graph. Pure (no Spring/DB): it asserts the same-project vs explicit-scope
 * resolution, alias handling, de-duplication, and that unsafe/garbage tokens are dropped rather than
 * producing corrupt links.
 */
class WikilinkParserTest {

    private final WikilinkParser parser = new WikilinkParser();

    private static Identity source() {
        return Identity.ofPage(
                WorkspaceId.of("acme"), ProjectId.of("web"), PagePath.of("concepts/recall.md"));
    }

    private static Identity target(String ws, String proj, String path) {
        return Identity.ofPage(WorkspaceId.of(ws), ProjectId.of(proj), PagePath.of(path));
    }

    @Test
    void sameProjectLinkResolvesAgainstSourceScope() {
        List<WikilinkRef> refs = parser.parse(source(), "see [[concepts/fusion.md]] for details");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).target()).isEqualTo(target("acme", "web", "concepts/fusion.md"));
        assertThat(refs.get(0).anchor()).isEqualTo("concepts/fusion.md");
    }

    @Test
    void mdSuffixIsOptionalInSourceText() {
        // PagePath normalization adds the .md the author omitted.
        List<WikilinkRef> refs = parser.parse(source(), "[[concepts/fusion]]");
        assertThat(refs.get(0).target()).isEqualTo(target("acme", "web", "concepts/fusion.md"));
    }

    @Test
    void aliasIsStrippedFromTargetButKeptInAnchor() {
        List<WikilinkRef> refs = parser.parse(source(), "[[concepts/fusion.md|Reciprocal Rank Fusion]]");
        assertThat(refs.get(0).target()).isEqualTo(target("acme", "web", "concepts/fusion.md"));
        assertThat(refs.get(0).anchor()).isEqualTo("concepts/fusion.md|Reciprocal Rank Fusion");
    }

    @Test
    void explicitScopeFormNamesACrossProjectTarget() {
        List<WikilinkRef> refs = parser.parse(source(), "compare [[other:lib:decisions/auth.md]]");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).target()).isEqualTo(target("other", "lib", "decisions/auth.md"));
    }

    @Test
    void scopedFormPathMayContainSlashesAfterTheScope() {
        List<WikilinkRef> refs = parser.parse(source(), "[[ws:proj:a/b/c/deep.md]]");
        assertThat(refs.get(0).target()).isEqualTo(target("ws", "proj", "a/b/c/deep.md"));
    }

    @Test
    void duplicateTargetsAreDeDuplicatedPreservingFirstSeenOrder() {
        List<WikilinkRef> refs = parser.parse(source(),
                "[[concepts/a.md]] then [[concepts/b.md]] then [[concepts/a.md]] again");
        assertThat(refs).extracting(r -> r.target().page().value())
                .containsExactly("concepts/a.md", "concepts/b.md");
    }

    @Test
    void multipleLinksOnOneLineAreAllFound() {
        List<WikilinkRef> refs = parser.parse(source(), "[[a.md]][[b.md]] [[c.md]]");
        assertThat(refs).extracting(r -> r.target().page().value())
                .containsExactly("a.md", "b.md", "c.md");
    }

    @Test
    void emptyOrWhitespaceTokensAreIgnored() {
        List<WikilinkRef> refs = parser.parse(source(), "[[]] and [[   ]] and [[|alias]]");
        assertThat(refs).isEmpty();
    }

    @Test
    void traversalAttemptsAreDroppedNotStored() {
        // PagePath rejects '..'; the parser must skip rather than throw or store a half-link.
        List<WikilinkRef> refs = parser.parse(source(), "[[../../etc/passwd]] [[ok.md]]");
        assertThat(refs).extracting(r -> r.target().page().value()).containsExactly("ok.md");
    }

    @Test
    void singleColonTokenIsTreatedAsAmbiguousScopeAndDropped() {
        // One colon is neither a clean same-project path nor a full scope → reject (defensive).
        List<WikilinkRef> refs = parser.parse(source(), "[[ws:concepts/x.md]] [[fine.md]]");
        assertThat(refs).extracting(r -> r.target().page().value()).containsExactly("fine.md");
    }

    @Test
    void scopedFormWithEmptyComponentsIsDropped() {
        List<WikilinkRef> refs = parser.parse(source(), "[[:proj:x.md]] [[ws::x.md]] [[ws:proj:]]");
        assertThat(refs).isEmpty();
    }

    @Test
    void nullOrEmptyBodyYieldsNoLinks() {
        assertThat(parser.parse(source(), null)).isEmpty();
        assertThat(parser.parse(source(), "")).isEmpty();
        assertThat(parser.parse(source(), "no links here at all")).isEmpty();
    }

    @Test
    void bracketsThatAreNotWikilinksAreIgnored() {
        List<WikilinkRef> refs = parser.parse(source(),
                "a [markdown](link.md) and [single] brackets and an array[0] index");
        assertThat(refs).isEmpty();
    }
}
