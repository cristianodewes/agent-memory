package com.agentmemory.links;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure parse tests for {@link WikiLinkParser} (issue #27 acceptance: "all three link scopes parse").
 * No DB — resolution to page ids is {@link WikiLinkServiceTest}.
 */
class WikiLinkParserTest {

    private final WikiLinkParser parser = new WikiLinkParser();

    /** Source page is acme/app/concepts/source.md unless a test needs otherwise. */
    private static final Identity SOURCE = Identity.ofPage(
            WorkspaceId.of("acme"), ProjectId.of("app"), PagePath.of("concepts/source.md"));

    private List<WikiLink> parse(String body) {
        return parser.parse(SOURCE, body);
    }

    // --- the three scopes --------------------------------------------------------------------------

    @Test
    void sameProjectLink() {
        List<WikiLink> links = parse("see [[concepts/recall]] for details");
        assertThat(links).hasSize(1);
        Identity t = links.get(0).target();
        assertThat(t.workspace().value()).isEqualTo("acme");
        assertThat(t.project().value()).isEqualTo("app");
        assertThat(t.page().value()).isEqualTo("concepts/recall.md");
    }

    @Test
    void siblingProjectLink() {
        List<WikiLink> links = parse("cross to [[other:decisions/storage]] here");
        assertThat(links).hasSize(1);
        Identity t = links.get(0).target();
        assertThat(t.workspace().value()).isEqualTo("acme"); // same workspace as source
        assertThat(t.project().value()).isEqualTo("other");
        assertThat(t.page().value()).isEqualTo("decisions/storage.md");
    }

    @Test
    void otherWorkspaceLink() {
        List<WikiLink> links = parse("far [[globex/platform:concepts/auth]] link");
        assertThat(links).hasSize(1);
        Identity t = links.get(0).target();
        assertThat(t.workspace().value()).isEqualTo("globex");
        assertThat(t.project().value()).isEqualTo("platform");
        assertThat(t.page().value()).isEqualTo("concepts/auth.md");
    }

    @Test
    void allThreeScopesInOneBody() {
        List<WikiLink> links = parse(
                "a [[same/here]] b [[sib:there]] c [[ws/proj:far/away]] end");
        assertThat(links).extracting(l -> l.target().project().value())
                .containsExactly("app", "sib", "proj");
        assertThat(links).extracting(l -> l.target().workspace().value())
                .containsExactly("acme", "acme", "ws");
    }

    // --- display alias -----------------------------------------------------------------------------

    @Test
    void displayAliasIsCapturedAsAnchorAndTargetResolved() {
        List<WikiLink> links = parse("read [[concepts/recall|the recall page]] now");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).anchor()).isEqualTo("the recall page");
        assertThat(links.get(0).target().page().value()).isEqualTo("concepts/recall.md");
    }

    @Test
    void aliasOnScopedLink() {
        List<WikiLink> links = parse("[[globex/platform:concepts/auth|Auth in Platform]]");
        assertThat(links.get(0).anchor()).isEqualTo("Auth in Platform");
        assertThat(links.get(0).target().workspace().value()).isEqualTo("globex");
    }

    @Test
    void noAliasMeansNullAnchor() {
        assertThat(parse("[[concepts/recall]]").get(0).anchor()).isNull();
    }

    // --- normalization & dedup ---------------------------------------------------------------------

    @Test
    void targetPathIsNormalizedLikeStoredPages() {
        // mixed case + missing .md + backslashes collapse to the canonical stored form
        List<WikiLink> links = parse("[[Concepts\\Recall]]");
        assertThat(links.get(0).target().page().value()).isEqualTo("concepts/recall.md");
    }

    @Test
    void duplicateTargetsCollapseKeepingFirstAnchor() {
        List<WikiLink> links = parse("[[concepts/recall|first]] then [[concepts/recall|second]]");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).anchor()).isEqualTo("first");
    }

    @Test
    void distinctScopesToSamePathAreDistinctLinks() {
        // same path, different project/workspace -> not duplicates
        List<WikiLink> links = parse("[[concepts/x]] [[sib:concepts/x]] [[w/p:concepts/x]]");
        assertThat(links).hasSize(3);
    }

    // --- malformed are skipped, not fatal ----------------------------------------------------------

    @Test
    void malformedLinksAreSkippedNotThrown() {
        List<WikiLink> links = parse(
                "good [[concepts/ok]] empty [[]] traversal [[../escape]] "
                        + "emptyscope [[:nopath]] blankpath [[proj:]] bad-ws [[/p:x]] "
                        + "still [[concepts/also-ok]]");
        assertThat(links).extracting(l -> l.target().page().value())
                .containsExactly("concepts/ok.md", "concepts/also-ok.md");
    }

    @Test
    void emptyOrNullBodyYieldsNoLinks() {
        assertThat(parser.parse(SOURCE, "")).isEmpty();
        assertThat(parser.parse(SOURCE, null)).isEmpty();
        assertThat(parser.parse(SOURCE, "no links here at all")).isEmpty();
    }

    @Test
    void pathWithColonInScopeSplitsOnFirstColonOnly() {
        // "sib:a/b/c" -> project sib, path a/b/c.md (path keeps its slashes)
        List<WikiLink> links = parse("[[sib:a/b/c]]");
        assertThat(links.get(0).target().project().value()).isEqualTo("sib");
        assertThat(links.get(0).target().page().value()).isEqualTo("a/b/c.md");
    }
}
