package com.agentmemory.autoimprove;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for {@link CuratorActionProposalSource#parseTarget} (issue #101): extracting the
 * dangling target id from a {@code DANGLING_CROSS_PROJECT} finding detail, which {@code CuratorService}
 * formats as {@code "dangling cross-project link -> <workspace/project/path> (unresolved Nd)"}.
 */
class CuratorActionProposalSourceTest {

    @Test
    void extractsTheTargetIdFromTheCuratorDetail() {
        String detail = "dangling cross-project link -> ws1/platform/concepts/auth.md (unresolved 31d)";
        assertThat(CuratorActionProposalSource.parseTarget(detail))
                .isEqualTo("ws1/platform/concepts/auth.md");
    }

    @Test
    void toleratesAMissingTrailingParenthesis() {
        String detail = "dangling cross-project link -> ws1/platform/concepts/auth.md";
        assertThat(CuratorActionProposalSource.parseTarget(detail))
                .isEqualTo("ws1/platform/concepts/auth.md");
    }

    @Test
    void returnsNullWhenNoArrowIsPresent() {
        assertThat(CuratorActionProposalSource.parseTarget("episodic page cold for 47d")).isNull();
        assertThat(CuratorActionProposalSource.parseTarget(null)).isNull();
    }
}
