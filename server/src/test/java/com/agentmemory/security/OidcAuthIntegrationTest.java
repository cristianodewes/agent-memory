package com.agentmemory.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.hooks.IngestService;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
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

/**
 * End-to-end OIDC validation (issue #39 PR2 — the server half of the RFC 8628 device grant): a JWT
 * minted by a <em>mock IdP</em> (an in-test RSA key whose public JWK is served over a tiny
 * {@link HttpServer}) authenticates against the real {@code RANDOM_PORT} server when its signature,
 * issuer, audience and expiry are valid, and is rejected otherwise. A valid token's subject becomes the
 * audit/observation actor (attribution, issue #39). OIDC subjects are regular users — forbidden on the
 * root-only admin routes. Runs against a throwaway {@code pgvector/pgvector:pg16}; the {@code test} LLM
 * provider boots past the DD-005 gate.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class OidcAuthIntegrationTest {

    private static final String ROOT = "root-secret-token";
    private static final String ISSUER = "https://test-idp.local";
    private static final String AUDIENCE = "agent-memory-test";

    /** The mock IdP's signing key (private); its public half is published at the JWKS endpoint. */
    private static final RSAKey SIGNING_KEY = generateKey();
    /** A different key NOT in the JWKS — used to forge a token with an unknown signature. */
    private static final RSAKey WRONG_KEY = generateKey();
    /** The mock IdP JWKS endpoint (JDK HttpServer), serving only {@link #SIGNING_KEY}'s public JWK. */
    private static final HttpServer JWKS_SERVER = startJwksServer(SIGNING_KEY);

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
        registry.add("agent-memory.auth.enabled", () -> "true");
        registry.add("agent-memory.auth.token", () -> ROOT);
        registry.add("agent-memory.auth.oidc.issuer", () -> ISSUER);
        registry.add("agent-memory.auth.oidc.audience", () -> AUDIENCE);
        registry.add("agent-memory.auth.oidc.jwks-uri",
                () -> "http://localhost:" + JWKS_SERVER.getAddress().getPort() + "/jwks");
        registry.add("agent-memory.auth.oidc.principal-claim", () -> "sub");
    }

    @AfterAll
    static void stopJwks() {
        JWKS_SERVER.stop(0);
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

    // --- happy path: a valid OIDC token authenticates and attributes to its subject ----------------

    @Test
    void validOidcTokenAuthenticatesOnNormalRoutes() {
        String jwt = mint("alice@oidc.example", AUDIENCE, ISSUER, Instant.now().plusSeconds(300));
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(jwt)).status())
                .isEqualTo(200);
    }

    @Test
    void validOidcHookIsAttributedToTheSubject() {
        String subject = "bob@oidc.example";
        String jwt = mint(subject, AUDIENCE, ISSUER, Instant.now().plusSeconds(300));
        String clientEventId = "evt-" + UUID.randomUUID();
        String body = "{"
                + "\"event\":\"UserPromptSubmit\","
                + "\"sessionId\":\"" + UUID.randomUUID() + "\","
                + "\"workspace\":\"acme\",\"project\":\"oidc-attr\","
                + "\"clientEventId\":\"" + clientEventId + "\","
                + "\"body\":\"who am i\",\"timestamp\":\"2026-06-25T12:00:00Z\"}";
        assertThat(http().postJson("/hook", body, "Authorization", AuthHttp.bearer(jwt)).status())
                .isEqualTo(202);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();

        String actor = jdbc().queryForObject(
                "SELECT actor FROM observations WHERE client_event_id = ?", String.class, clientEventId);
        assertThat(actor).isEqualTo(subject);
        Integer audited = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'observation.append' "
                        + "AND project = 'oidc-attr' AND actor = ?",
                Integer.class, subject);
        assertThat(audited).isGreaterThanOrEqualTo(1);
    }

    @Test
    void oidcSubjectIsNotRootSoAdminRoutesAreForbidden() {
        String jwt = mint("carol@oidc.example", AUDIENCE, ISSUER, Instant.now().plusSeconds(300));
        // /users/list is an admin route (ROLE_ROOT). An OIDC subject lacks root → 403 (not 401/503).
        assertThat(http().get("/users/list", "Authorization", AuthHttp.bearer(jwt)).status())
                .isEqualTo(403);
        // The root token clears the admin gate (then 503 here since no user store without a pepper).
        assertThat(http().get("/users/list", "Authorization", AuthHttp.bearer(ROOT)).status())
                .isEqualTo(503);
    }

    // --- rejection: signature, audience, issuer, expiry --------------------------------------------

    @Test
    void tokenSignedByAnUnknownKeyIsRejected() {
        String forged = mintWith(WRONG_KEY, "mallory@oidc.example", AUDIENCE, ISSUER,
                Instant.now().plusSeconds(300));
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(forged)).status())
                .isEqualTo(401);
    }

    @Test
    void wrongAudienceIsRejected() {
        String jwt = mint("dave@oidc.example", "some-other-app", ISSUER, Instant.now().plusSeconds(300));
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(jwt)).status())
                .isEqualTo(401);
    }

    @Test
    void wrongIssuerIsRejected() {
        String jwt = mint("erin@oidc.example", AUDIENCE, "https://evil-idp.local",
                Instant.now().plusSeconds(300));
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(jwt)).status())
                .isEqualTo(401);
    }

    @Test
    void expiredTokenIsRejected() {
        String jwt = mint("frank@oidc.example", AUDIENCE, ISSUER, Instant.now().minusSeconds(60));
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer(jwt)).status())
                .isEqualTo(401);
    }

    @Test
    void garbageBearerAndNoTokenAreRejected() {
        assertThat(http().get("/api/v1/workspaces", "Authorization", AuthHttp.bearer("not.a.jwt"))
                .status()).isEqualTo(401);
        assertThat(http().get("/api/v1/workspaces").status()).isEqualTo(401);
    }

    // --- mock IdP helpers --------------------------------------------------------------------------

    /** Mint a JWT signed by the mock IdP's published key. */
    private static String mint(String subject, String audience, String issuer, Instant expiry) {
        return mintWith(SIGNING_KEY, subject, audience, issuer, expiry);
    }

    private static String mintWith(RSAKey key, String subject, String audience, String issuer, Instant expiry) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(expiry))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("failed to mint test JWT", e);
        }
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key-" + UUID.randomUUID()).generate();
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate test RSA key", e);
        }
    }

    private static HttpServer startJwksServer(RSAKey key) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(jwks);
                }
            });
            server.setExecutor(null);
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException("failed to start mock JWKS server", e);
        }
    }
}
