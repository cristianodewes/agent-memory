package com.agentmemory.autoimprove;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.SessionId;
import com.agentmemory.curate.CuratorService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the production {@link ProposalSource} — the {@link CuratorProposalSource} adapter over the #29
 * {@link CuratorService} (issue #30) — against a real {@code pgvector/pgvector:pg16}. Seeds two pages that
 * share a title (so the curator's duplicate-title rule fires) and shows the adapter renders the findings
 * into a single {@code _lint/report.md} content proposal; that a clean project proposes nothing; and that
 * an unchanged report is not re-proposed (the per-session scheduler stays quiescent).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class CuratorProposalSourceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    @Autowired
    CuratorService curator;

    @Autowired
    PageRepository pages;

    private CuratorProposalSource source() {
        return new CuratorProposalSource(curator, pages);
    }

    private static Scope freshScope() {
        return Scope.of("ws" + UUID.randomUUID().toString().replace("-", ""), "proj");
    }

    private void seedPage(Scope scope, String path, String title, String body) {
        // create() materializes the parent workspace/project rows on demand; the DB row is enough for the
        // rule-based curator (which reads the relational index).
        pages.create(Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(path)), title, body);
    }

    @Test
    void curatorFindingsProduceASingleLintReportProposal() {
        Scope scope = freshScope();
        seedPage(scope, "concepts/a.md", "Recall design", "first");
        seedPage(scope, "concepts/b.md", "Recall design", "second"); // same title -> DUPLICATE_TITLE

        List<ProposedWrite> proposals = source().proposalsFor(scope, SessionId.newId());

        assertThat(proposals).hasSize(1);
        ProposedWrite p = proposals.get(0);
        assertThat(p.path()).isEqualTo("_lint/report.md");
        assertThat(p.title()).isEqualTo("Lint report");
        assertThat(p.body()).contains("DUPLICATE_TITLE").contains("[[concepts/a]]");
    }

    @Test
    void aCleanProjectProposesNothing() {
        Scope scope = freshScope();
        seedPage(scope, "concepts/only.md", "Unique", "no issues here");

        assertThat(source().proposalsFor(scope, SessionId.newId())).isEmpty();
    }

    @Test
    void anUnchangedReportIsNotReproposed() {
        Scope scope = freshScope();
        seedPage(scope, "concepts/a.md", "Dup", "first");
        seedPage(scope, "concepts/b.md", "Dup", "second");

        List<ProposedWrite> first = source().proposalsFor(scope, SessionId.newId());
        assertThat(first).hasSize(1);

        // Apply the proposal (write the _lint/report.md page with exactly the proposed body), then a
        // subsequent review must be quiescent — the report is unchanged.
        ProposedWrite p = first.get(0);
        seedPage(scope, p.path(), p.title(), p.body());

        assertThat(source().proposalsFor(scope, SessionId.newId())).isEmpty();
    }
}
