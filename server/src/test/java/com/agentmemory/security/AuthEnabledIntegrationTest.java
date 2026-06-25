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
 * End-to-end auth enforcement when {@code agent-memory.auth.enabled=true} (issue #38): protected routes
 * demand the shared token, {@code /web} additionally accepts it as an HTTP Basic password (and
 * challenges browsers), {@code /healthz} stays open, and a wrong token is rejected. Drives the real
 * {@code RANDOM_PORT} server over HTTP with explicit {@code Authorization} headers, against a throwaway
 * {@code pgvector/pgvector:pg16} so the DataSource-gated {@code /api/v1} is live. The offline
 * {@code test} provider boots past the DD-005 gate; the wiki sits on a temp dir.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false",
            "agent-memory.auth.enabled=true",
            "agent-memory.auth.token=s3cr3t-test-token"
        })
@Testcontainers
class AuthEnabledIntegrationTest {

    private static final String TOKEN = "s3cr3t-test-token";

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

    // --- protected API routes ----------------------------------------------------------------------

    @Test
    void apiRouteRejectsMissingToken() {
        AuthHttp.Resp r = http().get("/api/v1/workspaces");
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void apiRouteRejectsWrongToken() {
        AuthHttp.Resp r = http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer("nope"));
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void apiRouteAcceptsBearerToken() {
        AuthHttp.Resp r = http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(TOKEN));
        assertThat(r.status()).isEqualTo(200);
    }

    // --- /web: Basic challenge + token-as-password -------------------------------------------------

    @Test
    void webChallengesWithBasicWhenUnauthenticated() {
        AuthHttp.Resp r = http().get("/web/");
        assertThat(r.status()).isEqualTo(401);
        // A browser must be told to pop a Basic login (only /web gets this; the API stays headerless).
        assertThat(r.header("WWW-Authenticate")).isNotNull().startsWith("Basic");
    }

    @Test
    void apiRouteDoesNotSendBasicChallenge() {
        // API/MCP clients read the 401 status, not a browser dialog — no Basic challenge on /api.
        AuthHttp.Resp r = http().get("/api/v1/workspaces");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.header("WWW-Authenticate")).isNull();
    }

    @Test
    void webAcceptsBasicWithTokenAsPassword() {
        // Any username; the password is the token. This is what the browser resends after the challenge.
        AuthHttp.Resp r = http().get("/web/index.html", "Authorization", AuthHttp.basic("anyone", TOKEN));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().toLowerCase()).contains("<!doctype html");
    }

    @Test
    void webRejectsBasicWithWrongPassword() {
        AuthHttp.Resp r = http().get("/web/index.html", "Authorization", AuthHttp.basic("anyone", "wrong"));
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void webAcceptsBearerTokenToo() {
        AuthHttp.Resp r = http().get("/web/index.html", "Authorization", AuthHttp.bearer(TOKEN));
        assertThat(r.status()).isEqualTo(200);
    }

    // --- health stays open -------------------------------------------------------------------------

    @Test
    void healthEndpointIsPublic() {
        assertThat(http().get("/healthz").status()).isEqualTo(200);
        assertThat(http().get("/actuator/health").status()).isEqualTo(200);
    }

    // --- non-GET browser write guard ---------------------------------------------------------------

    @Test
    void basicAuthenticatedNonGetWithoutOriginIsBlocked() {
        // A browser (Basic) POST without a same-origin Origin header is a CSRF shape → 403, even though
        // the token is valid. (A real mutating endpoint isn't needed: the guard runs before routing.)
        AuthHttp.Resp r = http().send("POST", "/handoff", "Authorization", AuthHttp.basic("u", TOKEN));
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    void bearerAuthenticatedNonGetIsAllowedThrough() {
        // A Bearer (programmatic) POST is not subject to the browser-write guard; it passes the security
        // chain and reaches the handler (which may then 4xx on the body, but NOT 401/403 from auth).
        AuthHttp.Resp r = http().send("POST", "/handoff", "Authorization", AuthHttp.bearer(TOKEN));
        assertThat(r.status()).isNotIn(401, 403);
    }

    @Test
    void basicAuthenticatedNonGetWithSameOriginIsAllowedThrough() {
        String origin = "http://localhost:" + port;
        AuthHttp.Resp r = http().send("POST", "/handoff",
                "Authorization", AuthHttp.basic("u", TOKEN),
                "Origin", origin);
        assertThat(r.status()).isNotIn(401, 403);
    }
}
