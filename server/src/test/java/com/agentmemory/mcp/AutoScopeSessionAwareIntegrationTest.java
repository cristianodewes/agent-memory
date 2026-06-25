package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.CaptureSessionResolver;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end {@code auto_scope=session_aware} (issue #87): with per-session isolation on, an MCP call
 * that gives no explicit {@code workspace}/{@code project} resolves to <em>this capture session's</em>
 * most-recent project — so two sessions of the <strong>same</strong> user, in different projects,
 * default into their own lanes (the per-session counterpart to {@code per_actor}). Drives the real
 * {@link ScopeResolver} bean (wired {@code SESSION_AWARE} from config) against a throwaway
 * {@code pgvector/pgvector:pg16}, with observations attributed through the real ingest path keyed on
 * {@code observations.session_id}. The capture session id that the live MCP request reads from the
 * {@code X-Agent-Memory-Session} header (via {@link com.agentmemory.security.CaptureSessionHeaderFilter})
 * is supplied here by a settable test {@link CaptureSessionResolver}; the header→thread binding itself
 * is covered by {@code CaptureSessionHeaderFilterTest}.
 */
@SpringBootTest(
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false",
            "agent-memory.scope.auto=session_aware"
        })
@Import(AutoScopeSessionAwareIntegrationTest.FixedSessionConfig.class)
@Testcontainers
class AutoScopeSessionAwareIntegrationTest {

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

    /** A {@link CaptureSessionResolver} whose id the test sets, standing in for the request header. */
    @TestConfiguration
    static class FixedSessionConfig {
        static volatile String sessionId;

        @Bean
        CaptureSessionResolver fixedSessionResolver() {
            return () -> sessionId;
        }
    }

    @Autowired IngestService ingest;
    @Autowired ScopeResolver scopes;

    @AfterEach
    void reset() {
        SecurityContextHolder.clearContext();
        FixedSessionConfig.sessionId = null;
    }

    /** Attribute one observation in {@code project} to {@code session} (and {@code actor}), then drain. */
    private void ingestInSession(SessionId session, String actor, String project, Instant at) {
        HookPayload p = HookPayload.of(
                "UserPromptSubmit", session, WorkspaceId.of("acme"), ProjectId.of(project), at);
        assertThat(ingest.ingest(p, actor)).isEqualTo(IngestStatus.ACCEPTED);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void sessionAwareIsolatesTwoSessionsOfTheSameActor() {
        SessionId sessionA = SessionId.newId();
        SessionId sessionB = SessionId.newId();
        // SAME actor (alice), two sessions in DIFFERENT projects. Session B is the more recent activity,
        // so a per_actor/global default would resolve BOTH to "beta" — session_aware must not.
        ingestInSession(sessionA, "alice", "alpha", Instant.parse("2026-06-25T12:00:00Z"));
        ingestInSession(sessionB, "alice", "beta", Instant.parse("2026-06-25T12:00:05Z"));
        authenticateAs("alice");

        // Each session's no-scope call resolves to ITS OWN project, not the actor's global most-recent.
        FixedSessionConfig.sessionId = sessionA.value().toString();
        assertThat(scopes.resolve(Map.of()).projectSlug()).isEqualTo("alpha");

        FixedSessionConfig.sessionId = sessionB.value().toString();
        assertThat(scopes.resolve(Map.of()).projectSlug()).isEqualTo("beta");

        // An explicit scope still wins regardless of the session.
        Scope explicit = scopes.resolve(Map.of("workspace", "acme", "project", "alpha"));
        assertThat(explicit.projectSlug()).isEqualTo("alpha");
    }

    @Test
    void sessionAwareFailFastsWhenNoSessionIdReachedTheBoundary() {
        ingestInSession(SessionId.newId(), "alice", "alpha", Instant.parse("2026-06-25T12:00:00Z"));
        authenticateAs("alice");

        // The central #87 guard: with session_aware on but no session id on the request, NEVER fall back
        // to the global/most-recent scope (that would leak one session's project to another) — fail-fast.
        FixedSessionConfig.sessionId = null;
        assertThatThrownBy(() -> scopes.resolve(Map.of()))
                .isInstanceOf(ScopeResolver.ScopeUnresolvedException.class)
                .hasMessageContaining("session_aware")
                .hasMessageContaining(CaptureSessionResolver.SESSION_HEADER);

        // An explicit scope still resolves even without a session id (no default needed).
        assertThat(scopes.resolve(Map.of("workspace", "acme", "project", "alpha")).projectSlug())
                .isEqualTo("alpha");
    }

    @Test
    void sessionAwareReturnsNoDefaultForAnUnknownSession() {
        ingestInSession(SessionId.newId(), "alice", "alpha", Instant.parse("2026-06-25T12:00:00Z"));
        // A well-formed session id that captured nothing → no activity to default from (a clear error),
        // never another session's scope.
        FixedSessionConfig.sessionId = SessionId.newId().value().toString();
        assertThatThrownBy(() -> scopes.resolve(Map.of()))
                .isInstanceOf(ScopeResolver.ScopeUnresolvedException.class)
                .hasMessageContaining("no recent hook activity");
    }

    private static void authenticateAs(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", List.of()));
    }
}
