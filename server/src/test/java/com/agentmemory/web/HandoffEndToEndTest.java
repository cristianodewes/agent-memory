package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.hooks.HookPayload;
import com.agentmemory.hooks.IngestService;
import com.agentmemory.hooks.IngestStatus;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.TestDoubleProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Wiring proof for issue #22 over the <em>real</em> paths — the guard against a "loose class that is
 * never reached" (the dormant-trigger / dangling-method class of bug). Runs the full Spring context on
 * a {@code RANDOM_PORT} server against a throwaway {@code pgvector/pgvector:pg16} (Testcontainers):
 *
 * <ul>
 *   <li><b>REST is routed</b> — {@code POST /handoff}, {@code /handoff/accept}, {@code /handoff/cancel}
 *       are exercised over actual HTTP (JDK {@link HttpTestClient}), proving {@link HandoffController}
 *       is a discovered bean and the endpoints respond — not just that the method returns when called
 *       directly.</li>
 *   <li><b>session-end trigger is attached</b> — a real {@code SessionEnd} event driven through the
 *       autowired {@link IngestService} causes a handoff to be opened by the
 *       {@code SessionEndHandoffTrigger} that {@code HandoffConfiguration} registered as the ingest
 *       post-write listener. If that registration were dormant, no handoff row would appear and this
 *       fails.</li>
 * </ul>
 *
 * <p>The chat {@link LlmProvider} is overridden with a {@link Primary} scripted {@link TestDoubleProvider}
 * that returns a valid handoff document, so the autowired {@code HandoffService} (and thus the real
 * trigger and the real controller) produce a persistable handoff deterministically. The health-gate
 * still probes the by-name {@code test} provider, so startup is unaffected.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class HandoffEndToEndTest {

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

    /**
     * Override the chat provider (by type, {@link Primary}) so the autowired {@code HandoffService}
     * returns a valid handoff. The health gate injects {@code llmProvider} by name, so the real
     * {@code test} double still answers the startup probe.
     */
    @TestConfiguration
    static class ScriptedLlm {
        @Bean
        @Primary
        LlmProvider scriptedHandoffProvider() {
            return TestDoubleProvider.builder()
                    .chatResponder(ScriptedLlm::reply)
                    .build();
        }

        private static String reply(ChatRequest req) {
            // A valid typed handoff for any structured request; echo otherwise.
            return req.wantsStructuredOutput()
                    ? "{\"summary\":\"picked up where the last agent left off\","
                            + "\"openQuestions\":[\"ship it?\"],\"nextSteps\":[\"open the PR\"]}"
                    : "echo";
        }
    }

    @LocalServerPort int port;
    @Autowired IngestService ingest;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private HttpTestClient http() {
        return new HttpTestClient(port);
    }

    private static String ws() {
        return "ws" + UUID.randomUUID().toString().replace("-", "");
    }

    private HookPayload event(String ws, String proj, SessionId session, String evt, String body) {
        return new HookPayload(
                evt, null, session, WorkspaceId.of(ws), ProjectId.of(proj),
                null, "claude-code", null, body,
                null, null, null, null, "evt-" + UUID.randomUUID(),
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    /** Drive observations through the real ingest pipeline and block until written. */
    private void seed(String ws, String proj, SessionId session, String... events) {
        for (String e : events) {
            assertThat(ingest.ingest(event(ws, proj, session, e, "did work: " + e)))
                    .isEqualTo(IngestStatus.ACCEPTED);
        }
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();
    }

    private long openHandoffs(String ws, String proj) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM handoffs WHERE workspace = ? AND project = ? AND status = 'open'",
                Long.class, ws, proj);
    }

    // --- REST is actually routed -------------------------------------------------------------------

    @Test
    void restEndpointsAreRoutedAndDriveTheHandoffLifecycle() {
        String ws = ws();
        String proj = "proj";
        SessionId session = SessionId.newId();
        seed(ws, proj, session, "UserPromptSubmit", "PostToolUse");

        HttpTestClient http = http();

        // POST /handoff opens a handoff (routed to HandoffController, which calls the LLM-backed service).
        HttpTestClient.Response begin = http.postJson("/handoff",
                java.util.Map.of("workspace", ws, "project", proj, "sessionId", session.value().toString()));
        assertThat(begin.status()).as("POST /handoff is routed").isEqualTo(200);
        JsonNode opened = begin.json();
        assertThat(opened.get("status").asString()).isEqualTo("open");
        assertThat(opened.get("summary").asString()).isNotEmpty();

        // POST /handoff/accept consumes it (200), then a second accept is empty (204) — single-use.
        HttpTestClient.Response accept1 = http.postJson("/handoff/accept",
                java.util.Map.of("workspace", ws, "project", proj));
        assertThat(accept1.status()).isEqualTo(200);
        assertThat(accept1.json().get("status").asString()).isEqualTo("accepted");

        HttpTestClient.Response accept2 = http.postJson("/handoff/accept",
                java.util.Map.of("workspace", ws, "project", proj));
        assertThat(accept2.status()).as("single-use: nothing left to accept -> 204").isEqualTo(204);
    }

    @Test
    void postHandoffCancelIsRoutedAndExpiresTheOpenHandoff() {
        String ws = ws();
        String proj = "proj";
        SessionId session = SessionId.newId();
        seed(ws, proj, session, "UserPromptSubmit");

        HttpTestClient http = http();
        assertThat(http.postJson("/handoff",
                java.util.Map.of("workspace", ws, "project", proj, "sessionId", session.value().toString()))
                .status()).isEqualTo(200);
        assertThat(openHandoffs(ws, proj)).isEqualTo(1);

        HttpTestClient.Response cancel = http.postJson("/handoff/cancel",
                java.util.Map.of("workspace", ws, "project", proj));
        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.json().get("status").asString()).isEqualTo("expired");
        assertThat(openHandoffs(ws, proj)).as("cancel expired the open handoff").isZero();
    }

    @Test
    void postHandoffRejectsAMissingSessionIdOverHttp() {
        // A routed 400 (the controller's validation) — proves the body is bound and validated on the wire.
        HttpTestClient.Response r = http().postJson("/handoff",
                java.util.Map.of("workspace", ws(), "project", "proj"));
        assertThat(r.status()).isEqualTo(400);
    }

    // --- session-end trigger is attached to ingest (the #63-class guard) ---------------------------

    @Test
    void sessionEndEventThroughIngestOpensAHandoff() {
        String ws = ws();
        String proj = "proj";
        SessionId session = SessionId.newId();

        // No handoff yet.
        seed(ws, proj, session, "UserPromptSubmit", "PostToolUse");
        assertThat(openHandoffs(ws, proj)).as("no handoff before session-end").isZero();

        // A real SessionEnd flows through the autowired ingest pipeline; the registered
        // SessionEndHandoffTrigger (post-write listener) must open a handoff. We POLL rather than read
        // once: the trigger does the cheap session-end check on the ingest worker but dispatches the
        // (blocking) LLM generation to its own off-worker executor (issue #78), so awaitIdle() — which
        // only tracks the WRITE (inFlight), not that executor — does not cover the open. The handoff
        // appears shortly after the write returns.
        seed(ws, proj, session, "SessionEnd");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(openHandoffs(ws, proj))
                        .as("session-end opened a handoff via the attached trigger").isEqualTo(1));

        // And it is the project's open handoff, summarizing this session.
        Instant created = jdbc().queryForObject(
                "SELECT created_at FROM handoffs WHERE workspace = ? AND project = ? AND status = 'open'",
                Instant.class, ws, proj);
        assertThat(created).isNotNull();
        String fromSession = jdbc().queryForObject(
                "SELECT from_session::text FROM handoffs WHERE workspace = ? AND project = ? "
                        + "AND status = 'open'", String.class, ws, proj);
        assertThat(fromSession).isEqualTo(session.value().toString());
    }
}
