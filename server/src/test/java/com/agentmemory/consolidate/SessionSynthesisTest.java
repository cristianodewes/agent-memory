package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.MarkdownDocument;
import com.agentmemory.wiki.WikiPaths;
import com.agentmemory.wiki.WikiWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end session synthesis (issue #18) over a throwaway {@code pgvector/pgvector:pg16}
 * (Testcontainers) and a real wiki git repo (the JUnit temp data dir), proving the acceptance
 * criteria:
 *
 * <ul>
 *   <li><b>Golden synthesis</b>: a session of observations compiles (via the LLM double, scripted to
 *       return the structured-JSON contract) into {@code sessions/<id>.md} — a real page row plus the
 *       atomic markdown file with a Summary / Decisions / Follow-ups body.</li>
 *   <li><b>Idempotency</b>: re-running the same (unchanged) session is a no-op — no second LLM call,
 *       no new page version; once it gains an observation it re-synthesizes.</li>
 *   <li><b>Long-session chunking</b>: a session whose transcript exceeds the budget drives the
 *       map-reduce path (multiple chat calls: chunk summaries + the final synthesis).</li>
 * </ul>
 *
 * <p>The {@link SessionSynthesizer} is constructed directly with a scripted {@link TestDoubleProvider}
 * (deterministic, offline) and the autowired {@link PageRepository}/{@link WikiWriter}, so the test
 * controls the LLM replies and counts calls. The full context boots with the {@code test} provider
 * purely to satisfy the DD-005 gate. Each test uses a fresh {@code (workspace, project, session)}.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class SessionSynthesisTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @org.junit.jupiter.api.io.TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    @Autowired DataSource dataSource;
    @Autowired PageRepository pages;
    @Autowired WikiWriter wikiWriter;
    @Autowired WikiPaths wikiPaths;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- golden JSON the LLM double returns for the final synthesis --------------------------------

    private static final String GOLDEN_JSON = """
            {
              "title": "Recall arm + CI fix",
              "summary": "Implemented the pgvector recall arm and fixed the compose smoke test.",
              "decisions": ["Chose pgvector cosine over ivfflat"],
              "follow_ups": ["Wire embed-on-write in the reindex"],
              "open_questions": [],
              "highlights": ["All 279 tests pass"]
            }
            """;

    /** A double whose chat() returns the golden synthesis JSON for a structured request, else echoes. */
    private static TestDoubleProvider goldenLlm(AtomicInteger chatCount) {
        return TestDoubleProvider.builder()
                .chatResponder(req -> {
                    chatCount.incrementAndGet();
                    return req.wantsStructuredOutput() ? GOLDEN_JSON : "chunk summary text";
                })
                .build();
    }

    private SessionSynthesizer synthesizerWith(TestDoubleProvider llm) {
        return new SessionSynthesizer(
                new JdbcSessionObservationReader(jdbc()), llm, pages, wikiWriter);
    }

    private SessionSynthesizer synthesizerWith(TestDoubleProvider llm, int charBudget) {
        return new SessionSynthesizer(
                new JdbcSessionObservationReader(jdbc()), llm, pages, wikiWriter,
                new SynthesisPrompts(), new SessionSynthesisParser(),
                new SessionMarkdownRenderer(), charBudget);
    }

    // --- seeding -----------------------------------------------------------------------------------

    private record Scope(String ws, String proj, UUID wsId, UUID projId) {}

    private Scope freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return new Scope(ws, proj, wsId, projId);
    }

    private SessionId seedSession(Scope s) {
        SessionId sid = SessionId.newId();
        jdbc().update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sid.value(), s.wsId(), s.projId(), s.ws(), s.proj(),
                java.sql.Timestamp.from(Instant.parse("2026-06-25T12:00:00Z")));
        return sid;
    }

    private void seedObservation(Scope s, SessionId sid, String kind, String payload) {
        seedObservationAt(s, sid, kind, payload, Instant.now());
    }

    private void seedObservationAt(Scope s, SessionId sid, String kind, String payload, Instant at) {
        jdbc().update(
                "INSERT INTO observations (id, session_id, workspace_id, project_id, workspace, project, "
                        + "kind, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sid.value(), s.wsId(), s.projId(), s.ws(), s.proj(),
                kind, payload, java.sql.Timestamp.from(at));
    }

    private Identity sessionPage(Scope s, SessionId sid) {
        return Identity.ofPage(WorkspaceId.of(s.ws()), ProjectId.of(s.proj()),
                SessionSynthesizer.pagePathFor(sid));
    }

    // --- 1. golden synthesis -----------------------------------------------------------------------

    @Test
    void sessionEndProducesASessionsPageWithSummaryDecisionsAndFollowUps() throws Exception {
        Scope s = freshScope();
        SessionId sid = seedSession(s);
        seedObservation(s, sid, "user-prompt", "implement the vector recall arm");
        seedObservation(s, sid, "post-tool-use", "added PageEmbeddingStore and VectorArm");
        seedObservation(s, sid, "session-end", "done");

        AtomicInteger calls = new AtomicInteger();
        SynthesisOutcome outcome = synthesizerWith(goldenLlm(calls)).synthesize(sid);

        assertThat(outcome.status()).isEqualTo(SynthesisOutcome.Status.WRITTEN);
        assertThat(calls.get()).isEqualTo(1); // short transcript -> single synthesis call, no map step

        // DB row exists as the latest version of sessions/<id>.md.
        Identity page = sessionPage(s, sid);
        PageRecord latest = pages.readLatest(page).orElseThrow();
        assertThat(latest.page().title()).isEqualTo("Recall arm + CI fix");
        assertThat(latest.page().path().value()).isEqualTo("sessions/" + sid.value() + ".md");

        // Markdown file written with the rendered body.
        Path file = wikiPaths.resolve(page);
        String onDisk = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(onDisk).contains("kind: \"sessions\""); // frontmatter values are quoted
        assertThat(onDisk).contains("## Summary").contains("pgvector recall arm");
        assertThat(onDisk).contains("## Decisions").contains("Chose pgvector cosine over ivfflat");
        assertThat(onDisk).contains("## Follow-ups").contains("Wire embed-on-write in the reindex");
        // file is self-describing / parseable
        assertThat(MarkdownDocument.parse(onDisk).identity()).isEqualTo(page);
    }

    // --- 2. idempotency ----------------------------------------------------------------------------

    @Test
    void reRunningAnUnchangedSessionIsANoOpAndDoesNotDuplicate() {
        Scope s = freshScope();
        SessionId sid = seedSession(s);
        seedObservation(s, sid, "user-prompt", "do the thing");
        seedObservation(s, sid, "session-end", "done");

        AtomicInteger calls = new AtomicInteger();
        SessionSynthesizer synth = synthesizerWith(goldenLlm(calls));

        SynthesisOutcome first = synth.synthesize(sid);
        assertThat(first.status()).isEqualTo(SynthesisOutcome.Status.WRITTEN);
        UUID firstVersion = first.page().id().value();
        assertThat(calls.get()).isEqualTo(1);

        // Re-run with the SAME observation count: no LLM call, no new version.
        SynthesisOutcome second = synth.synthesize(sid);
        assertThat(second.status()).isEqualTo(SynthesisOutcome.Status.SKIPPED);
        assertThat(calls.get()).isEqualTo(1); // unchanged — synthesis was skipped
        assertThat(pages.readLatest(sessionPage(s, sid)).orElseThrow().id().value())
                .isEqualTo(firstVersion); // still the same single latest version

        // Exactly one page row for the path (no duplicate page).
        Integer rows = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ?",
                Integer.class, s.ws(), s.proj(), "sessions/" + sid.value() + ".md");
        assertThat(rows).isEqualTo(1);

        // After a NEW observation arrives, re-synthesis happens (new version, another LLM call).
        seedObservation(s, sid, "post-tool-use", "follow-up work");
        SynthesisOutcome third = synth.synthesize(sid);
        assertThat(third.status()).isEqualTo(SynthesisOutcome.Status.WRITTEN);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(pages.readLatest(sessionPage(s, sid)).orElseThrow().page().supersedes()).isNotNull();
    }

    @Test
    void aContentChangeAtEqualObservationCountStillReSynthesizes() {
        // The fingerprint is count + latest timestamp, so a change that keeps the COUNT equal (a
        // delete plus a newer capture) is still detected as stale — count alone would wrongly skip it.
        Scope s = freshScope();
        SessionId sid = seedSession(s);
        seedObservationAt(s, sid, "user-prompt", "first", Instant.parse("2026-06-25T12:00:00Z"));
        seedObservationAt(s, sid, "session-end", "done", Instant.parse("2026-06-25T12:05:00Z"));

        AtomicInteger calls = new AtomicInteger();
        SessionSynthesizer synth = synthesizerWith(goldenLlm(calls));
        assertThat(synth.synthesize(sid).status()).isEqualTo(SynthesisOutcome.Status.WRITTEN);
        assertThat(calls.get()).isEqualTo(1);

        // Replace the latest observation with a NEWER one — count stays 2, but max(created_at) advances.
        jdbc().update("DELETE FROM observations WHERE session_id = ? AND kind = 'session-end'", sid.value());
        seedObservationAt(s, sid, "session-end", "done later", Instant.parse("2026-06-25T13:00:00Z"));

        SynthesisOutcome reRun = synth.synthesize(sid);
        assertThat(reRun.status())
                .as("equal count but newer content must re-synthesize, not skip")
                .isEqualTo(SynthesisOutcome.Status.WRITTEN);
        assertThat(calls.get()).isEqualTo(2);
    }

    // --- 3. long-session chunking ------------------------------------------------------------------

    @Test
    void longSessionDrivesTheMapReduceChunkingPath() {
        Scope s = freshScope();
        SessionId sid = seedSession(s);
        // Seed many sizable observations so the transcript far exceeds a tiny budget.
        for (int i = 0; i < 12; i++) {
            seedObservation(s, sid, "post-tool-use", "step " + i + " " + "detail ".repeat(40));
        }
        seedObservation(s, sid, "session-end", "done");

        AtomicInteger calls = new AtomicInteger();
        // Tiny budget forces several chunks; each chunk = one summary call, plus one final synthesis.
        SynthesisOutcome outcome = synthesizerWith(goldenLlm(calls), 400).synthesize(sid);

        assertThat(outcome.status()).isEqualTo(SynthesisOutcome.Status.WRITTEN);
        // map (>=2 chunk summaries) + reduce (1 synthesis) ⇒ strictly more than the single-call path.
        assertThat(calls.get()).isGreaterThan(1);
        assertThat(pages.readLatest(sessionPage(s, sid))).isPresent();
    }

    // --- no observations ---------------------------------------------------------------------------

    @Test
    void sessionWithNoObservationsWritesNothing() {
        Scope s = freshScope();
        SessionId sid = seedSession(s);

        AtomicInteger calls = new AtomicInteger();
        SynthesisOutcome outcome = synthesizerWith(goldenLlm(calls)).synthesize(sid);

        assertThat(outcome.status()).isEqualTo(SynthesisOutcome.Status.NO_OBSERVATIONS);
        assertThat(calls.get()).isZero();
        assertThat(pages.readLatest(sessionPage(s, sid))).isEmpty();
    }

    // --- trigger seam -----------------------------------------------------------------------------

    @Test
    void triggerSynthesizesOnSessionEndAndIgnoresOtherKinds() {
        Scope s = freshScope();
        SessionId sid = seedSession(s);
        seedObservation(s, sid, "user-prompt", "work");
        seedObservation(s, sid, "session-end", "done");

        AtomicInteger calls = new AtomicInteger();
        var trigger = new SessionConsolidationTrigger(synthesizerWith(goldenLlm(calls)));

        // A non-trigger kind does nothing.
        assertThat(trigger.onObservation(sid, com.agentmemory.core.ObservationKind.USER_PROMPT)).isNull();
        assertThat(calls.get()).isZero();

        // session-end fires synthesis.
        SynthesisOutcome out = trigger.onObservation(sid, com.agentmemory.core.ObservationKind.SESSION_END);
        assertThat(out).isNotNull();
        assertThat(out.wasWritten()).isTrue();
        assertThat(pages.readLatest(sessionPage(s, sid))).isPresent();
    }

    @Test
    void triggerSwallowsSynthesisFailureToProtectIngest() {
        Scope s = freshScope();
        SessionId sid = seedSession(s);
        seedObservation(s, sid, "session-end", "done");

        // An LLM double that returns invalid JSON for the structured request → ConsolidationException
        // inside synthesize(); the trigger must swallow it (returns null) so ingest is unaffected.
        TestDoubleProvider badLlm = TestDoubleProvider.builder()
                .chatResponder(req -> "not json")
                .build();
        var trigger = new SessionConsolidationTrigger(synthesizerWith(badLlm));

        assertThat(trigger.onObservation(sid, com.agentmemory.core.ObservationKind.SESSION_END)).isNull();
        // nothing was written despite the trigger firing
        assertThat(pages.readLatest(sessionPage(s, sid))).isEmpty();
    }
}
