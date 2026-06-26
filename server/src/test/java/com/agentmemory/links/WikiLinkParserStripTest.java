package com.agentmemory.links;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link WikiLinkParser#stripLinksTo} (issue #101 link-fix prune): a pure, IO-free
 * removal of the {@code [[...]]} markup that resolves to a given target, reusing the exact parser grammar
 * + source-relative resolution. Proves it strips the dangling target (plain, aliased, duplicated) while
 * leaving other links and surrounding prose untouched.
 */
class WikiLinkParserStripTest {

    private final WikiLinkParser parser = new WikiLinkParser();

    private static final Identity SOURCE =
            Identity.ofPage(WorkspaceId.of("w"), ProjectId.of("app"), PagePath.of("decisions/use.md"));

    /** The cross-project target a {@code [[platform:concepts/auth]]} link resolves to. */
    private static final Identity TARGET =
            Identity.ofPage(WorkspaceId.of("w"), ProjectId.of("platform"), PagePath.of("concepts/auth.md"));

    @Test
    void removesTheDanglingLinkButKeepsOtherLinksAndProse() {
        String body = "see [[platform:concepts/auth]] and [[concepts/local]] for details";

        String pruned = parser.stripLinksTo(SOURCE, body, TARGET);

        assertThat(pruned).doesNotContain("platform:concepts/auth");
        assertThat(pruned).contains("[[concepts/local]]"); // a different link survives
        assertThat(pruned).contains("see ").contains(" for details"); // prose intact
    }

    @Test
    void removesTheAliasedAndDuplicatedFormsOfTheSameTarget() {
        String body = "a [[platform:concepts/auth|Auth]] then again [[ platform:concepts/auth ]] end";

        String pruned = parser.stripLinksTo(SOURCE, body, TARGET);

        assertThat(pruned).doesNotContain("platform:concepts/auth");
        assertThat(pruned).doesNotContain("[["); // both occurrences gone
        assertThat(pruned).contains("a ").contains(" then again ").contains(" end");
    }

    @Test
    void leavesUnrelatedLinksAndMalformedMarkupUntouched() {
        String body = "[[concepts/local]] and [[]] and [[other:x]] stay";

        String pruned = parser.stripLinksTo(SOURCE, body, TARGET);

        assertThat(pruned).isEqualTo(body); // nothing matched the target
    }

    @Test
    void nullOrEmptyOrNullTargetReturnsBodyUnchanged() {
        assertThat(parser.stripLinksTo(SOURCE, null, TARGET)).isNull();
        assertThat(parser.stripLinksTo(SOURCE, "", TARGET)).isEmpty();
        String body = "see [[platform:concepts/auth]]";
        assertThat(parser.stripLinksTo(SOURCE, body, null)).isEqualTo(body);
    }
}
