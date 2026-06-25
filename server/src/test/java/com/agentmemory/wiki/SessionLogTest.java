package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast (DB-less) unit tests for {@link SessionLog}: the {@code log.md} line format + atomic ordered
 * append (including concurrent sessions writing the same file), and the write-once {@code raw/}
 * archive. The end-to-end "exactly one line + one raw entry per accepted event through the single
 * writer" is covered by {@link com.agentmemory.hooks.SessionLogIngestIntegrationTest}.
 */
class SessionLogTest {

    private static final WorkspaceId WS = WorkspaceId.of("acme");
    private static final ProjectId PROJECT = ProjectId.of("agent-memory");
    private static final Identity IDENTITY = Identity.ofProject(WS, PROJECT);

    private static SessionLog newSessionLog(Path data) {
        return new SessionLog(new WikiPaths(data.resolve("wiki")), data.resolve("raw"));
    }

    private static Observation observation(SessionId session, ObservationKind kind,
            String sourceEvent, String payload, Instant at) {
        return new Observation(
                new ObservationId(Uuid7.randomUuid()), session, IDENTITY, kind, sourceEvent, null, payload, at);
    }

    private static Path logFile(Path data) {
        return data.resolve("wiki").resolve("acme").resolve("agent-memory").resolve("log.md");
    }

    private static Path rawFile(Path data, SessionId session, Observation obs) {
        return data.resolve("raw").resolve("acme").resolve("agent-memory")
                .resolve(session.value().toString()).resolve(obs.id().value() + ".json");
    }

    @Test
    void appendsOneFormattedLedgerLinePerEvent(@TempDir Path data) throws Exception {
        SessionLog sessionLog = newSessionLog(data);
        SessionId session = SessionId.newId();

        sessionLog.record(observation(session, ObservationKind.USER_PROMPT, "UserPromptSubmit",
                "title: why is recall slow?\nbody: details", Instant.parse("2026-06-25T12:00:00Z")));
        sessionLog.record(observation(session, ObservationKind.POST_TOOL_USE, "PostToolUse",
                "tool: grep", Instant.parse("2026-06-25T12:00:01Z")));

        List<String> lines = Files.readAllLines(logFile(data));
        assertThat(lines).hasSize(2);
        // ## [<timestamp>] <event> | <title>  — title derived from the first payload line, label stripped.
        assertThat(lines.get(0))
                .isEqualTo("## [2026-06-25T12:00:00Z] UserPromptSubmit | why is recall slow?");
        // Second event's first payload line is "tool: grep" (no title: label) -> used verbatim.
        assertThat(lines.get(1))
                .isEqualTo("## [2026-06-25T12:00:01Z] PostToolUse | tool: grep");
    }

    @Test
    void ledgerLineFallsBackToKindWhenNoSourceEventAndOmitsTitleWhenNoPayload(@TempDir Path data)
            throws Exception {
        SessionLog sessionLog = newSessionLog(data);
        SessionId session = SessionId.newId();

        // No source event -> canonical kind wire token; empty payload -> no " | title" suffix.
        sessionLog.record(observation(session, ObservationKind.STOP, null, "",
                Instant.parse("2026-06-25T12:00:02Z")));

        assertThat(Files.readAllLines(logFile(data)))
                .containsExactly("## [2026-06-25T12:00:02Z] stop");
    }

    @Test
    void newlinesInPayloadNeverBreakTheOneLinePerEventLedger(@TempDir Path data) throws Exception {
        SessionLog sessionLog = newSessionLog(data);
        SessionId session = SessionId.newId();

        sessionLog.record(observation(session, ObservationKind.USER_PROMPT, "UserPromptSubmit",
                "title: line one\nstill same ledger line?\n", Instant.parse("2026-06-25T12:00:03Z")));

        List<String> lines = Files.readAllLines(logFile(data));
        assertThat(lines).hasSize(1); // exactly one physical line despite multi-line payload
        assertThat(lines.get(0)).startsWith("## [2026-06-25T12:00:03Z] UserPromptSubmit | line one");
    }

    @Test
    void writesImmutableRawEntryWithSanitizedFields(@TempDir Path data) throws Exception {
        SessionLog sessionLog = newSessionLog(data);
        SessionId session = SessionId.newId();
        Observation obs = observation(session, ObservationKind.USER_PROMPT, "UserPromptSubmit",
                "title: hello", Instant.parse("2026-06-25T12:00:00Z"));

        sessionLog.record(obs);

        Path raw = rawFile(data, session, obs);
        assertThat(Files.exists(raw)).isTrue();
        assertThat(Files.readString(raw))
                .contains("\"id\":\"" + obs.id().value() + "\"")
                .contains("\"sessionId\":\"" + session.value() + "\"")
                .contains("\"workspace\":\"acme\"")
                .contains("\"project\":\"agent-memory\"")
                .contains("\"kind\":\"user-prompt\"")
                .contains("\"sourceEvent\":\"UserPromptSubmit\"")
                .contains("\"payload\":\"title: hello\"")
                .contains("\"createdAt\":\"2026-06-25T12:00:00Z\"");
    }

    @Test
    void rawArchiveIsWriteOnce(@TempDir Path data) throws Exception {
        SessionLog sessionLog = newSessionLog(data);
        SessionId session = SessionId.newId();
        Observation obs = observation(session, ObservationKind.NOTIFICATION, "Notification",
                "body: ping", Instant.parse("2026-06-25T12:00:00Z"));

        sessionLog.record(obs);
        Path raw = rawFile(data, session, obs);
        String original = Files.readString(raw);

        // Re-recording the SAME observation id must refuse to overwrite the raw entry (write-once).
        assertThatThrownBy(() -> sessionLog.record(obs))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("write-once");
        assertThat(Files.readString(raw)).isEqualTo(original); // untouched
    }

    @Test
    void concurrentAppendsToSameLogDoNotInterleave(@TempDir Path data) throws Exception {
        SessionLog sessionLog = newSessionLog(data);

        int threads = 8;
        int perThread = 30;
        int total = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> tokens = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new java.util.ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int threadNo = t;
                // Each thread is a distinct concurrent session, all writing the SAME project log.md.
                SessionId session = SessionId.newId();
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            String token = "tok-" + threadNo + "-" + i;
                            tokens.add(token);
                            sessionLog.record(observation(session, ObservationKind.POST_TOOL_USE,
                                    "PostToolUse", "title: " + token, Instant.parse("2026-06-25T12:00:00Z")));
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS); // surface any worker failure
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        List<String> lines = Files.readAllLines(logFile(data));
        // Exactly one intact line per event — none lost, none split/interleaved.
        assertThat(lines).hasSize(total);
        assertThat(lines).allMatch(l -> l.startsWith("## [2026-06-25T12:00:00Z] PostToolUse | tok-"));
        // Every token appears exactly once (a torn write would corrupt or drop a token).
        Set<String> seen = lines.stream()
                .map(l -> l.substring(l.indexOf("| ") + 2).strip())
                .collect(Collectors.toCollection(HashSet::new));
        assertThat(seen).isEqualTo(tokens);
        assertThat(seen).hasSize(total);
    }
}
