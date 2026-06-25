package com.agentmemory.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.hooks.IngestService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end multi-user mode (issue #39): with a token pepper set, the root token manages users and
 * gates the admin routes, while a per-user token authenticates as its own identity on normal routes
 * and is rejected on admin routes. Exercises the full token lifecycle (add → list → expire → revive →
 * rotate) through the real {@code RANDOM_PORT} server over HTTP, against a throwaway
 * {@code pgvector/pgvector:pg16}; the offline {@code test} provider boots past the DD-005 gate.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false",
            "agent-memory.auth.enabled=true",
            "agent-memory.auth.token=root-secret-token",
            "agent-memory.auth.token-pepper=test-pepper-value"
        })
@Testcontainers
class MultiUserAuthIntegrationTest {

    private static final String ROOT = "root-secret-token";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    @Autowired DataSource dataSource;
    @Autowired IngestService ingest;

    private AuthHttp http() {
        return new AuthHttp(port);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * POST a minimal valid {@code /hook} event as {@code token} and return its {@code clientEventId}
     * (the lookup key). Blocks until the async writer has drained, so the row is queryable on return.
     */
    private String postHookAs(String token, String project) {
        String clientEventId = "evt-" + UUID.randomUUID();
        String body = "{"
                + "\"event\":\"UserPromptSubmit\","
                + "\"sessionId\":\"" + UUID.randomUUID() + "\","
                + "\"workspace\":\"acme\","
                + "\"project\":\"" + project + "\","
                + "\"clientEventId\":\"" + clientEventId + "\","
                + "\"body\":\"who am i\","
                + "\"timestamp\":\"2026-06-25T12:00:00Z\"}";
        AuthHttp.Resp r = http().postJson("/hook", body, "Authorization", AuthHttp.bearer(token));
        assertThat(r.status()).as("POST /hook: %s", r.body()).isEqualTo(202);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).as("ingest drained").isTrue();
        return clientEventId;
    }

    /** Create a user with the root token and return its freshly issued token. */
    private String addUser(String username) {
        AuthHttp.Resp r = http().postJson("/users/add",
                "{\"username\":\"" + username + "\"}", "Authorization", AuthHttp.bearer(ROOT));
        assertThat(r.status()).as("add user: %s", r.body()).isEqualTo(201);
        JsonNode body = JSON.readTree(r.body());
        assertThat(body.get("username").asString()).isEqualTo(username);
        String token = body.get("token").asString();
        assertThat(token).isNotBlank();
        return token;
    }

    // --- per-user token authenticates on normal routes, rejected on admin --------------------------

    @Test
    void perUserTokenWorksOnNormalRoutesButNotAdmin() {
        String userToken = addUser("alice");

        // Normal route: a per-user token is accepted.
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(userToken)).status())
                .isEqualTo(200);

        // Admin route (user management): a per-user token is forbidden (lacks the root role).
        assertThat(http().get("/users/list", "Authorization", AuthHttp.bearer(userToken)).status())
                .isEqualTo(403);

        // The root token may use admin routes.
        assertThat(http().get("/users/list", "Authorization", AuthHttp.bearer(ROOT)).status())
                .isEqualTo(200);
    }

    @Test
    void unknownTokenIsRejected() {
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer("not-a-real-token"))
                .status()).isEqualTo(401);
        assertThat(http().get("/api/v1/workspaces").status()).isEqualTo(401); // no token at all
    }

    // --- lifecycle: expire / revive ----------------------------------------------------------------

    @Test
    void expireRevokesThenReviveRestores() {
        String userToken = addUser("bob");
        String authz = AuthHttp.bearer(userToken);

        assertThat(http().get("/api/v1/workspaces", "Authorization", authz).status()).isEqualTo(200);

        // Expire bob (root action): the token immediately stops resolving.
        assertThat(http().postJson("/users/expire", "{\"username\":\"bob\"}",
                "Authorization", AuthHttp.bearer(ROOT)).status()).isEqualTo(200);
        assertThat(http().get("/api/v1/workspaces", "Authorization", authz).status()).isEqualTo(401);

        // Revive bob: the same token resolves again.
        assertThat(http().postJson("/users/revive", "{\"username\":\"bob\"}",
                "Authorization", AuthHttp.bearer(ROOT)).status()).isEqualTo(200);
        assertThat(http().get("/api/v1/workspaces", "Authorization", authz).status()).isEqualTo(200);
    }

    // --- lifecycle: rotate-token -------------------------------------------------------------------

    @Test
    void rotateTokenInvalidatesOldAndIssuesNew() {
        String oldToken = addUser("carol");

        AuthHttp.Resp rot = http().postJson("/users/rotate-token", "{\"username\":\"carol\"}",
                "Authorization", AuthHttp.bearer(ROOT));
        assertThat(rot.status()).isEqualTo(200);
        String newToken = JSON.readTree(rot.body()).get("token").asString();
        assertThat(newToken).isNotBlank().isNotEqualTo(oldToken);

        // Old token no longer authenticates; new one does.
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(oldToken)).status())
                .isEqualTo(401);
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(newToken)).status())
                .isEqualTo(200);
    }

    // --- add validation ----------------------------------------------------------------------------

    @Test
    void addingDuplicateUserConflicts() {
        addUser("dave");
        AuthHttp.Resp dup = http().postJson("/users/add", "{\"username\":\"dave\"}",
                "Authorization", AuthHttp.bearer(ROOT));
        assertThat(dup.status()).isEqualTo(409);
    }

    @Test
    void listShowsAddedUsersWithStatus() {
        addUser("erin");
        AuthHttp.Resp r = http().get("/users/list", "Authorization", AuthHttp.bearer(ROOT));
        assertThat(r.status()).isEqualTo(200);
        JsonNode users = JSON.readTree(r.body()).get("users");
        boolean found = false;
        for (JsonNode u : users) {
            if ("erin".equals(u.get("username").asString())) {
                found = true;
                assertThat(u.get("status").asString()).isEqualTo("active");
                // Never leak token material in a listing.
                assertThat(u.has("token")).isFalse();
                assertThat(u.has("token_hash")).isFalse();
            }
        }
        assertThat(found).as("erin present in user list").isTrue();
    }

    // --- user management itself is admin-gated -----------------------------------------------------

    @Test
    void creatingUsersRequiresRootToken() {
        String userToken = addUser("frank");
        // A non-root (per-user) caller cannot create users.
        AuthHttp.Resp r = http().postJson("/users/add", "{\"username\":\"mallory\"}",
                "Authorization", AuthHttp.bearer(userToken));
        assertThat(r.status()).isEqualTo(403);
    }

    // --- attribution: the authenticated user is recorded on the rows they produce -------------------

    @Test
    void perUserHookIsAttributedToThatUserInObservationAndAudit() {
        String userToken = addUser("grace");
        String clientEventId = postHookAs(userToken, "attribution-user");

        // The observation row carries the per-user actor (issue #39 acceptance: identities recorded).
        String observationActor = jdbc().queryForObject(
                "SELECT actor FROM observations WHERE client_event_id = ?", String.class, clientEventId);
        assertThat(observationActor).isEqualTo("grace");

        // The mutation's audit_log row is attributed to the same user.
        Integer auditedForGrace = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log "
                        + "WHERE action = 'observation.append' AND project = 'attribution-user' "
                        + "  AND actor = 'grace'",
                Integer.class);
        assertThat(auditedForGrace).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rootHookIsAttributedToRoot() {
        // The shared root token authenticates as the principal "root"; its captures are filed under it.
        String clientEventId = postHookAs(ROOT, "attribution-root");

        String observationActor = jdbc().queryForObject(
                "SELECT actor FROM observations WHERE client_event_id = ?", String.class, clientEventId);
        assertThat(observationActor).isEqualTo("root");
    }
}
