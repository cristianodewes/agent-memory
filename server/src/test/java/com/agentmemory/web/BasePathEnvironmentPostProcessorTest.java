package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the base-path → context-path normalization (issue #35). */
class BasePathEnvironmentPostProcessorTest {

    @Test
    void rootAndBlankContributeNothing() {
        assertThat(BasePathEnvironmentPostProcessor.normalize(null)).isNull();
        assertThat(BasePathEnvironmentPostProcessor.normalize("")).isNull();
        assertThat(BasePathEnvironmentPostProcessor.normalize("   ")).isNull();
        assertThat(BasePathEnvironmentPostProcessor.normalize("/")).isNull();
    }

    @Test
    void addsLeadingSlashAndStripsTrailingSlash() {
        assertThat(BasePathEnvironmentPostProcessor.normalize("agent-memory")).isEqualTo("/agent-memory");
        assertThat(BasePathEnvironmentPostProcessor.normalize("/agent-memory")).isEqualTo("/agent-memory");
        assertThat(BasePathEnvironmentPostProcessor.normalize("/agent-memory/")).isEqualTo("/agent-memory");
        assertThat(BasePathEnvironmentPostProcessor.normalize("  /agent-memory//  "))
                .isEqualTo("/agent-memory");
    }

    @Test
    void preservesNestedPrefixes() {
        assertThat(BasePathEnvironmentPostProcessor.normalize("/team/agent-memory"))
                .isEqualTo("/team/agent-memory");
    }
}
