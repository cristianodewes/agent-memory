package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.consolidate.Consolidator;
import com.agentmemory.consolidate.MemoryExplore;
import com.agentmemory.consolidate.SessionSynthesizer;
import com.agentmemory.handoff.HandoffService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Wiring of the synthesis route (issue #156) in the full context: with
 * {@code agent-memory.synthesis.llm.auth.model} set, the session-page final synthesis, the thematic
 * pages ({@link Consolidator}) and the handoff get the {@code synthesisLlmProvider}, while the chunk
 * summaries and the other consumers keep the primary. The routing behaviour itself is covered by
 * {@code SessionSynthesisTest} and {@link FallbackLlmProviderTest}; this pins which bean each consumer
 * receives, so a future qualifier change cannot silently send the bulk work to the synthesis model.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.synthesis.llm.auth.model=synthesis-model",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class SynthesisLlmRouteWiringTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    @Autowired @Qualifier("llmProvider") LlmProvider primary;
    @Autowired @Qualifier("synthesisLlmProvider") LlmProvider synthesis;
    @Autowired SessionSynthesizer sessionSynthesizer;
    @Autowired Consolidator consolidator;
    @Autowired HandoffService handoffService;
    @Autowired MemoryExplore memoryExplore;

    @Test
    void configuredModelBuildsASeparateRouteWithFallback() {
        // The `test` provider key always returns the one shared double, whatever model is asked for, so
        // the model swap itself is asserted in SynthesisLlmProviderResolutionTest; here the property must
        // bind and resolve to the fallback-wrapped route instead of the primary instance.
        assertThat(synthesis).isNotSameAs(primary).isInstanceOf(FallbackLlmProvider.class);
    }

    @Test
    void pagesAndHandoffUseTheSynthesisRoute() {
        assertThat(ReflectionTestUtils.getField(sessionSynthesizer, "synthesisLlm")).isSameAs(synthesis);
        assertThat(ReflectionTestUtils.getField(consolidator, "llm")).isSameAs(synthesis);
        assertThat(ReflectionTestUtils.getField(handoffService, "llm")).isSameAs(synthesis);
    }

    @Test
    void chunkSummariesAndExploreStayOnThePrimary() {
        assertThat(ReflectionTestUtils.getField(sessionSynthesizer, "llm")).isSameAs(primary);
        assertThat(ReflectionTestUtils.getField(memoryExplore, "llm")).isSameAs(primary);
    }
}
