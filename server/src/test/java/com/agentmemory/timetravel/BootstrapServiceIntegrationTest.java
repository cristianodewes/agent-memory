package com.agentmemory.timetravel;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiWriter;
import java.nio.file.Files;
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
 * Bootstrap acceptance test (issue #34): a single structured LLM pass over a project's history
 * produces seed pages that are written through the real store + wiki + git path. The LLM is a
 * scripted {@link TestDoubleProvider} (the "LLM double" the issue calls for) so the seed-page content
 * is deterministic; everything else — the digest collection, the page write, the markdown file, the
 * git commit, the DB row — is real.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class BootstrapServiceIntegrationTest {

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

    @Autowired PageRepository pages;
    @Autowired WikiWriter wikiWriter;
    @Autowired AgentMemoryConfig config;

    @Test
    void bootstrapCompilesRepoHistoryIntoSeedPagesViaOneLlmPass(@TempDir Path repo) throws Exception {
        // A small source repo with the signals the digest gathers.
        Files.writeString(repo.resolve("README.md"),
                "# Acme Service\n\nA payments gateway. See docs for the architecture.\n");
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(repo.resolve("docs/architecture.md"),
                "# Architecture\n\nGateway -> ledger -> bank rails.\n");

        // Script the single structured reply to two seed pages.
        String reply = """
                {"pages":[
                  {"path":"concepts/architecture.md","title":"Architecture overview",
                   "body":"The gateway routes to the ledger then the bank rails."},
                  {"path":"conventions/rules.md","title":"Conventions",
                   "body":"All money values are integer minor units."}
                ]}""";
        TestDoubleProvider scripted = TestDoubleProvider.builder()
                .chatResponder(req -> reply)
                .build();

        DefaultBootstrapService bootstrap =
                new DefaultBootstrapService(scripted, pages, wikiWriter, config.dataDir());

        WorkspaceId ws = WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
        BootstrapResult result = bootstrap.bootstrap(ws.value(), "proj", repo);

        // Exactly one LLM pass (the "one-shot" requirement), and it was a structured request.
        assertThat(scripted.chatCalls()).hasSize(1);
        assertThat(scripted.chatCalls().get(0).wantsStructuredOutput()).isTrue();

        // Both seed pages were written and reported.
        assertThat(result.performed()).isTrue();
        assertThat(result.pagesWritten())
                .containsExactlyInAnyOrder("concepts/architecture.md", "conventions/rules.md");

        // They are real, latest pages with the scripted content (DB row).
        Identity arch = Identity.ofPage(ws, ProjectId.of("proj"), PagePath.of("concepts/architecture.md"));
        assertThat(pages.readLatest(arch)).isPresent();
        assertThat(pages.readLatest(arch).get().page().body()).contains("bank rails");

        // And the markdown file + git commit landed on disk.
        Path archFile = config.wikiDir().resolve(ws.value()).resolve("proj")
                .resolve("concepts/architecture.md");
        assertThat(Files.exists(archFile)).isTrue();
        assertThat(Files.readString(archFile)).contains("Architecture overview");
    }

    @Test
    void bootstrapWithNoHistoryWritesNoPages(@TempDir Path emptyRepo) {
        TestDoubleProvider scripted = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"pages\":[]}")
                .build();
        DefaultBootstrapService bootstrap =
                new DefaultBootstrapService(scripted, pages, wikiWriter, config.dataDir());

        WorkspaceId ws = WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
        BootstrapResult result = bootstrap.bootstrap(ws.value(), "proj", emptyRepo);

        // An empty digest short-circuits before any LLM call.
        assertThat(scripted.chatCalls()).isEmpty();
        assertThat(result.performed()).isTrue();
        assertThat(result.pagesWritten()).isEmpty();
    }
}
