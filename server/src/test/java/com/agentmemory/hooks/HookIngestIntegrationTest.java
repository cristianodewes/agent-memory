package com.agentmemory.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.web.HookController;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end ingest tests against a throwaway {@code pgvector/pgvector:pg16} Postgres (issue #8). The
 * full pipeline runs: {@link IngestService} assembles + sanitizes a {@link HookPayload}, the bounded
 * queue's single worker drains to {@link com.agentmemory.store.PostgresObservationWriter}, and rows
 * land in {@code observations} + {@code audit_log}. {@link IngestService#awaitIdle(Duration)} makes
 * the async pipeline deterministic.
 *
 * <p>Covers the issue's acceptance criteria that need a real database: every accepted event appears
 * in {@code observations} and {@code audit_log} with the identity tuple; a replayed batch creates no
 * duplicates (idempotency on {@code clientEventId}); concurrent posts do not interleave-corrupt rows;
 * and one malformed item in a batch does not fail the rest (partial-accept). Saturation/429 is a
 * separate fast unit test ({@link IngestServiceBackpressureTest}).
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class HookIngestIntegrationTest {

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

    @Autowired IngestService ingest;
    @Autowired HookController controller;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static HookPayload event(SessionId session, String event, String clientEventId, String body) {
        return new HookPayload(
                event, null, session,
                WorkspaceId.of("acme"), ProjectId.of("agent-memory"),
                null, "claude-code", null, body,
                null, null, null, null, clientEventId,
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    @Test
    void acceptedEventLandsInObservationsAndAuditWithIdentity() {
        SessionId session = SessionId.newId();
        String clientEventId = "evt-" + UUID.randomUUID();
        assertThat(ingest.ingest(event(session, "UserPromptSubmit", clientEventId, "why is recall slow?")))
                .isEqualTo(IngestStatus.ACCEPTED);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();

        Map<String, Object> row = jdbc().queryForMap(
                "SELECT workspace, project, kind, source_event, payload "
                        + "FROM observations WHERE session_id = ? AND client_event_id = ?",
                session.value(), clientEventId);
        assertThat(row.get("workspace")).isEqualTo("acme");
        assertThat(row.get("project")).isEqualTo("agent-memory");
        assertThat(row.get("kind")).isEqualTo("user-prompt");
        assertThat(row.get("source_event")).isEqualTo("UserPromptSubmit");
        assertThat((String) row.get("payload")).contains("why is recall slow?");

        // A session row was upserted for the FK, and an audit entry records the mutation + identity.
        Integer sessions = jdbc().queryForObject(
                "SELECT count(*) FROM sessions WHERE id = ?", Integer.class, session.value());
        assertThat(sessions).isEqualTo(1);
        Integer audits = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log "
                        + "WHERE action = 'observation.append' AND workspace = 'acme' AND project = 'agent-memory' "
                        + "  AND entity_type = 'observation'",
                Integer.class);
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sessionEndStampsEndedAt() {
        SessionId session = SessionId.newId();
        ingest.ingest(event(session, "SessionStart", "s-" + UUID.randomUUID(), null));
        ingest.ingest(event(session, "SessionEnd", "e-" + UUID.randomUUID(), null));
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();

        Object endedAt = jdbc().queryForMap(
                "SELECT ended_at FROM sessions WHERE id = ?", session.value()).get("ended_at");
        assertThat(endedAt).as("session-end stamps ended_at").isNotNull();
    }

    @Test
    void replayingABatchCreatesNoDuplicates() {
        SessionId session = SessionId.newId();
        // Three distinct events, each with a stable clientEventId — the spool a client would drain.
        List<JsonNode> batch = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            batch.add(mapper.valueToTree(event(session, "PostToolUse", "spool-" + i, "ran step " + i)));
        }

        ResponseEntity<Map<String, Object>> first = controller.hookBatch(batch);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();

        // Replay the identical batch (a retried drain): every item dedupes on (session, clientEventId).
        ResponseEntity<Map<String, Object>> second = controller.hookBatch(batch);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();

        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM observations WHERE session_id = ?", Integer.class, session.value());
        assertThat(count).as("replayed batch must not duplicate rows").isEqualTo(3);

        // And no second audit entry per replayed event (only genuine inserts are audited).
        Integer audits = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log al "
                        + "JOIN observations o ON o.id = al.entity_id "
                        + "WHERE o.session_id = ?",
                Integer.class, session.value());
        assertThat(audits).isEqualTo(3);
    }

    @Test
    void oneMalformedItemDoesNotFailTheBatch() {
        SessionId session = SessionId.newId();
        List<JsonNode> batch = new ArrayList<>();
        batch.add(mapper.valueToTree(event(session, "UserPromptSubmit", "ok-1", "good one")));
        // A malformed item: missing the required "event" field — must be skipped, not abort the batch.
        batch.add(mapper.readTree("{\"sessionId\":\"" + session.value()
                + "\",\"workspace\":\"acme\",\"project\":\"agent-memory\","
                + "\"timestamp\":\"2026-06-25T12:00:00Z\"}"));
        batch.add(mapper.valueToTree(event(session, "Stop", "ok-2", null)));

        ResponseEntity<Map<String, Object>> resp = controller.hookBatch(batch);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();

        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("accepted")).isEqualTo(2);
        assertThat(body.get("invalid")).isEqualTo(1);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); // something was accepted

        // The two good events persisted despite the bad one in the middle.
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM observations WHERE session_id = ?", Integer.class, session.value());
        assertThat(count).isEqualTo(2);
    }

    @Test
    void concurrentPostsDoNotInterleaveCorruptRows() throws Exception {
        SessionId session = SessionId.newId();
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadNo = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String id = "c-" + threadNo + "-" + i;
                        ingest.ingest(event(session, "PostToolUse", id, "payload " + id));
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        assertThat(ingest.awaitIdle(Duration.ofSeconds(20))).isTrue();

        // Every distinct clientEventId produced exactly one row, none corrupted/duplicated/lost.
        Integer total = jdbc().queryForObject(
                "SELECT count(*) FROM observations WHERE session_id = ?", Integer.class, session.value());
        assertThat(total).isEqualTo(threads * perThread);
        Integer distinct = jdbc().queryForObject(
                "SELECT count(DISTINCT client_event_id) FROM observations WHERE session_id = ?",
                Integer.class, session.value());
        assertThat(distinct).isEqualTo(threads * perThread);
        // The denormalized identity tuple is intact on every row.
        Integer mismatched = jdbc().queryForObject(
                "SELECT count(*) FROM observations "
                        + "WHERE session_id = ? AND (workspace <> 'acme' OR project <> 'agent-memory')",
                Integer.class, session.value());
        assertThat(mismatched).isEqualTo(0);
    }
}
