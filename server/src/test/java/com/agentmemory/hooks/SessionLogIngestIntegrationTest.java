package com.agentmemory.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.web.HookController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests for issue #11 against a throwaway {@code pgvector/pgvector:pg16} Postgres and a
 * real data dir: the full single-writer path ({@link IngestService} -> bounded queue ->
 * {@link com.agentmemory.store.PostgresObservationWriter}) now also produces the {@code log.md}
 * ledger line and the immutable {@code raw/} entry for every accepted event, committed alongside the
 * DB row. Verifies the issue's acceptance criteria that touch the filesystem: exactly one line + one
 * raw entry per accepted event, and that a deduped replay adds neither.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class SessionLogIngestIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // A real data dir so wiki/log.md and raw/ are written to a known, assertable location.
        registry.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    @Autowired IngestService ingest;
    @Autowired HookController controller;
    @Autowired ObjectMapper mapper;

    private static HookPayload event(SessionId session, String event, String clientEventId, String body) {
        return new HookPayload(
                event, null, session,
                WorkspaceId.of("acme"), ProjectId.of("agent-memory"),
                null, "claude-code", null, body,
                null, null, null, null, clientEventId,
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    private Path logFile() {
        return dataDir.resolve("wiki").resolve("acme").resolve("agent-memory").resolve("log.md");
    }

    private Path rawSessionDir(SessionId session) {
        return dataDir.resolve("raw").resolve("acme").resolve("agent-memory")
                .resolve(session.value().toString());
    }

    @Test
    void eachAcceptedEventYieldsOneLogLineAndOneRawEntry() throws Exception {
        SessionId session = SessionId.newId();
        long linesBefore = countLogLines();

        ingest.ingest(event(session, "UserPromptSubmit", "evt-" + UUID.randomUUID(), "why is recall slow?"));
        ingest.ingest(event(session, "PostToolUse", "evt-" + UUID.randomUUID(), "ran grep"));
        ingest.ingest(event(session, "SessionEnd", "evt-" + UUID.randomUUID(), null));
        assertThat(ingest.awaitIdle(Duration.ofSeconds(15))).isTrue();

        // Exactly three new ledger lines for this session's three accepted events.
        List<String> sessionLines = logLinesContaining("UserPromptSubmit", "PostToolUse", "SessionEnd");
        assertThat(countLogLines() - linesBefore).isEqualTo(3);
        assertThat(sessionLines).anyMatch(l -> l.contains("why is recall slow?"));

        // Exactly one raw/ entry per accepted event, under this session's directory.
        Path rawDir = rawSessionDir(session);
        assertThat(Files.isDirectory(rawDir)).isTrue();
        try (Stream<Path> files = Files.list(rawDir)) {
            List<Path> entries = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertThat(entries).hasSize(3);
            // Each raw entry carries the denormalized identity + sanitized payload.
            String anyJson = Files.readString(entries.get(0));
            assertThat(anyJson).contains("\"workspace\":\"acme\"").contains("\"project\":\"agent-memory\"");
        }
    }

    @Test
    void dedupedReplayAddsNoSecondLogLineOrRawEntry() throws Exception {
        SessionId session = SessionId.newId();
        List<JsonNode> batch = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            batch.add(mapper.valueToTree(event(session, "PostToolUse", "spool-" + i, "step " + i)));
        }

        controller.hookBatch(batch);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(15))).isTrue();
        long linesAfterFirst = countLogLines();
        long rawAfterFirst = countRawEntries(session);
        assertThat(rawAfterFirst).isEqualTo(3);

        // Replay the identical batch (a retried drain): every item dedupes on (session, clientEventId),
        // so NO new observation row is inserted -> the side effect never fires -> no new line/raw entry.
        controller.hookBatch(batch);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(15))).isTrue();

        assertThat(countLogLines()).as("replay must not append duplicate ledger lines")
                .isEqualTo(linesAfterFirst);
        assertThat(countRawEntries(session)).as("replay must not duplicate raw entries").isEqualTo(3);
    }

    // --- helpers -------------------------------------------------------------------------------

    private long countLogLines() throws Exception {
        Path log = logFile();
        if (!Files.exists(log)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(log)) {
            return lines.filter(l -> !l.isBlank()).count();
        }
    }

    private List<String> logLinesContaining(String... anyOf) throws Exception {
        Path log = logFile();
        if (!Files.exists(log)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(log)) {
            return lines.filter(l -> Stream.of(anyOf).anyMatch(l::contains)).toList();
        }
    }

    private long countRawEntries(SessionId session) throws Exception {
        Path dir = rawSessionDir(session);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json")).count();
        }
    }
}
