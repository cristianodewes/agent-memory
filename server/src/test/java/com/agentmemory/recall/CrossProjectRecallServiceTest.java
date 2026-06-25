package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.agentmemory.store.PageRepository;

/**
 * End-to-end tests for cross-project recall (issue #29) against a throwaway {@code pgvector/pgvector:pg16}
 * Postgres (Testcontainers), proving the three acceptance points: named {@code scopes} fan-out,
 * {@code global} fan-out across every project, per-hit scope annotation, and isolation (no hit from a
 * project that was not requested).
 *
 * <p>Pages are seeded through the real {@link PageRepository#create} writer path. Each test embeds a
 * unique nonce token in the page bodies and queries for it, so a {@code global} search — which
 * enumerates every scope on the shared container — deterministically returns only that test's pages.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class CrossProjectRecallServiceTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired CrossProjectRecallService crossRecall;
    @Autowired PageRepository pages;

    private static WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** A nonce token unique to one test, so a global query returns only that test's seeded pages. */
    private static String nonce() {
        return "ztoken" + UUID.randomUUID().toString().replace("-", "");
    }

    private void seed(WorkspaceId ws, String project, String path, String title, String body) {
        pages.create(Identity.ofPage(ws, ProjectId.of(project), PagePath.of(path)), title, body);
    }

    @Test
    void namedScopesFanOutAnnotateOriginAndStayIsolated() {
        WorkspaceId ws = freshWorkspace();
        String tok = nonce();
        seed(ws, "alpha", "concepts/a.md", "Alpha page", "alpha discusses " + tok + " in depth");
        seed(ws, "beta", "concepts/b.md", "Beta page", "beta also covers " + tok + " thoroughly");
        seed(ws, "gamma", "concepts/c.md", "Gamma page", "gamma mentions " + tok + " too");

        CrossProjectRecallResult r = crossRecall.search(
                tok, List.of(Scope.of(ws.value(), "alpha"), Scope.of(ws.value(), "beta")), 10);

        // Only the two requested scopes were searched (isolation): gamma never appears.
        assertThat(r.scopes()).containsExactly(
                Scope.of(ws.value(), "alpha"), Scope.of(ws.value(), "beta"));
        assertThat(r.hits()).isNotEmpty();
        assertThat(r.hits()).allSatisfy(h -> assertThat(h.workspace()).isEqualTo(ws.value()));
        assertThat(r.hits()).extracting(ScopedRecallHit::project)
                .containsOnly("alpha", "beta")          // gamma excluded
                .contains("alpha", "beta");             // both requested scopes represented
        // Each hit carries its origin project matching the page it points at.
        assertThat(r.hits()).allSatisfy(h -> {
            if ("concepts/a.md".equals(h.hit().path())) {
                assertThat(h.project()).isEqualTo("alpha");
            }
            if ("concepts/b.md".equals(h.hit().path())) {
                assertThat(h.project()).isEqualTo("beta");
            }
        });
        // Ranks are the global 1..n positions, contiguous and ordered.
        for (int i = 0; i < r.hits().size(); i++) {
            assertThat(r.hits().get(i).hit().rank()).isEqualTo(i + 1);
        }
    }

    @Test
    void globalSearchesEveryProjectIncludingOnesNotNamed() {
        WorkspaceId ws = freshWorkspace();
        String tok = nonce();
        seed(ws, "alpha", "concepts/a.md", "Alpha page", "alpha covers " + tok);
        seed(ws, "beta", "concepts/b.md", "Beta page", "beta covers " + tok);
        seed(ws, "gamma", "concepts/c.md", "Gamma page", "gamma covers " + tok);

        CrossProjectRecallResult r = crossRecall.searchGlobal(tok, 50);

        // Global reached all three projects (the nonce keeps other tests' data out of the result).
        assertThat(r.hits()).extracting(ScopedRecallHit::project)
                .contains("alpha", "beta", "gamma");
        assertThat(r.hits()).allSatisfy(h -> assertThat(h.workspace()).isEqualTo(ws.value()));
        assertThat(r.rawFallback()).isFalse();
    }

    @Test
    void limitCapsTheMergedResult() {
        WorkspaceId ws = freshWorkspace();
        String tok = nonce();
        seed(ws, "alpha", "p1.md", "P1", tok + " one");
        seed(ws, "beta", "p2.md", "P2", tok + " two");
        seed(ws, "gamma", "p3.md", "P3", tok + " three");

        CrossProjectRecallResult r = crossRecall.search(
                tok,
                List.of(Scope.of(ws.value(), "alpha"), Scope.of(ws.value(), "beta"),
                        Scope.of(ws.value(), "gamma")),
                2);
        assertThat(r.hits()).hasSize(2);
        assertThat(r.hits().get(0).hit().rank()).isEqualTo(1);
        assertThat(r.hits().get(1).hit().rank()).isEqualTo(2);
    }

    @Test
    void emptyScopesIsRejected() {
        assertThatThrownBy(() -> crossRecall.search("anything", List.of(), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
