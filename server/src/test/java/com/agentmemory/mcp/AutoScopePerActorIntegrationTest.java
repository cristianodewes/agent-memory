package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.hooks.HookPayload;
import com.agentmemory.hooks.IngestService;
import com.agentmemory.hooks.IngestStatus;
import com.agentmemory.recall.Scope;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end {@code auto_scope=per_actor} (issue #39): with isolation on, an MCP call that gives no
 * explicit {@code workspace}/{@code project} resolves to the <em>authenticated caller's own</em>
 * most-recent project, not the server's globally most-recent one. Drives the real {@link ScopeResolver}
 * bean (wired {@code PER_ACTOR} from config) against a throwaway {@code pgvector/pgvector:pg16}, with
 * observations attributed through the real ingest path and the actor read from the security context —
 * the same {@link com.agentmemory.security.SecurityContextActorResolver} the live MCP request thread uses.
 */
@SpringBootTest(
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false",
            "agent-memory.scope.auto=per_actor"
        })
@Testcontainers
class AutoScopePerActorIntegrationTest {

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

    @Autowired IngestService ingest;
    @Autowired ScopeResolver scopes;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Attribute one observation in {@code project} to {@code actor}, at an explicit time, and drain. */
    private void ingestAs(String actor, String project, Instant at) {
        HookPayload p = HookPayload.of(
                "UserPromptSubmit", SessionId.newId(), WorkspaceId.of("acme"), ProjectId.of(project), at);
        assertThat(ingest.ingest(p, actor)).isEqualTo(IngestStatus.ACCEPTED);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();
    }

    private static void authenticateAs(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", List.of()));
    }

    @Test
    void perActorResolvesEachUsersOwnMostRecentProject() {
        // bob's "beta" is the globally most-recent activity; alice's only project is "alpha".
        ingestAs("alice", "alpha", Instant.parse("2026-06-25T12:00:00Z"));
        ingestAs("bob", "beta", Instant.parse("2026-06-25T12:00:05Z"));

        // No explicit scope: each authenticated caller defaults into their OWN most-recent project.
        authenticateAs("alice");
        assertThat(scopes.resolve(Map.of()).projectSlug()).isEqualTo("alpha");

        authenticateAs("bob");
        assertThat(scopes.resolve(Map.of()).projectSlug()).isEqualTo("beta");

        // An explicit scope still wins regardless of the per_actor mode.
        Scope explicit = scopes.resolve(Map.of("workspace", "acme", "project", "alpha"));
        assertThat(explicit.projectSlug()).isEqualTo("alpha");

        // With no authenticated actor, per_actor falls back to the global most-recent (single_slot).
        SecurityContextHolder.clearContext();
        assertThat(scopes.resolve(Map.of()).projectSlug()).isEqualTo("beta");
    }
}
