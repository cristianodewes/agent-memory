package com.agentmemory.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The secure-by-default fast-path (issue #38): with auth left at its default ({@code enabled=false}) on
 * the loopback bind, every route is reachable with no credentials — no ceremony for the single-user
 * laptop. This is the counterpart to {@link AuthEnabledIntegrationTest}; it pins that the chain does not
 * accidentally start protecting routes when auth is off. Real {@code RANDOM_PORT} server over a
 * throwaway {@code pgvector/pgvector:pg16} (so {@code /api/v1} is live); offline {@code test} provider.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
            // agent-memory.auth.enabled defaults to false — loopback-only, no auth.
        })
@Testcontainers
class AuthDisabledIntegrationTest {

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

    @LocalServerPort int port;

    private AuthHttp http() {
        return new AuthHttp(port);
    }

    @Test
    void apiIsReachableWithoutAuth() {
        assertThat(http().get("/api/v1/workspaces").status()).isEqualTo(200);
    }

    @Test
    void webIsReachableWithoutAuth() {
        AuthHttp.Resp r = http().get("/web/index.html");
        assertThat(r.status()).isEqualTo(200);
        // And no Basic challenge is offered when auth is off.
        assertThat(r.header("WWW-Authenticate")).isNull();
    }

    @Test
    void healthIsReachableWithoutAuth() {
        assertThat(http().get("/healthz").status()).isEqualTo(200);
    }

    @Test
    void browserStyleNonGetIsNotGuardedWhenAuthDisabled() {
        // With auth off there is no Basic-authenticated principal, so the browser-write guard never
        // engages: a POST is handled normally (no 403 from the guard).
        AuthHttp.Resp r = http().send("POST", "/handoff");
        assertThat(r.status()).isNotEqualTo(403);
    }
}
