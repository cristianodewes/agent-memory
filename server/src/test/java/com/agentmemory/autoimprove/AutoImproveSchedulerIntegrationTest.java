package com.agentmemory.autoimprove;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.consolidate.SessionObservationReader;
import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.PageId;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.Session;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.eval.EvalGate;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.LlmRateLimitGate;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.store.PageWriteCallback;
import java.util.Optional;
import com.agentmemory.eval.EvalGateProperties;
import com.agentmemory.recall.Scope;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * Integration coverage for the auto-improve scheduler (issue #30) against a real
 * {@code pgvector/pgvector:pg16}. Seeds real {@code sessions} rows and drives {@link
 * AutoImproveScheduler#tick()} directly (the SmartLifecycle timer is never started — these assert the
 * unit of work, not the cadence). Proves: a due session is reviewed and its proposals applied; a tick
 * with no {@link ProposalSource} burns nothing; ticks don't overlap; first sight of a project doesn't
 * retro-review its history; and a failing review is recorded {@code failed} and retried only under the
 * attempt cap.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class AutoImproveSchedulerIntegrationTest {

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

    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbc;
    private JdbcPendingWriteRepository pending;
    private JdbcAutoImproveStateRepository state;

    /** What the gate's apply path was asked to write (the production applier replaced by this fake). */
    private final List<ProposedWrite> applied = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        pending = new JdbcPendingWriteRepository(jdbc);
        state = new JdbcAutoImproveStateRepository(jdbc);
        applied.clear();
        // dueSessions() is global across projects (the scheduler reviews every project); these methods
        // share one container, so clear the auto-improve + session state for per-test isolation.
        jdbc.update("DELETE FROM auto_improve_session_review");
        jdbc.update("DELETE FROM auto_improve_watermark");
        jdbc.update("DELETE FROM pending_writes");
        jdbc.update("DELETE FROM sessions");
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private record Seed(String ws, String proj, UUID wsId, UUID projId) {
        Scope scope() {
            return Scope.of(ws, proj);
        }
    }

    private Seed freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc.update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return new Seed(ws, proj, wsId, projId);
    }

    /** Seed a finished session whose {@code ended_at} is {@code endedAt} (started a minute earlier). */
    private SessionId seedFinishedSession(Seed seed, Instant endedAt) {
        SessionId sid = SessionId.newId();
        jdbc.update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at, "
                        + "ended_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                sid.value(), seed.wsId(), seed.projId(), seed.ws(), seed.proj(),
                Timestamp.from(endedAt.minusSeconds(60)), Timestamp.from(endedAt));
        return sid;
    }

    private AutoImproveGate gate(AutoImproveProperties props) {
        return new AutoImproveGate(
                pending, (scope, write) -> applied.add(write), props,
                new EvalGate(new EvalGateProperties(false, null, null, null, 0, null)));
    }

    private static AutoImproveProperties props(int maxAttempts, int maxPerTick) {
        return new AutoImproveProperties(
                false, maxAttempts, maxPerTick, new AutoImproveProperties.Scheduler(false, null), null, null);
    }

    private static ProposedWrite ruleEdit() {
        return new ProposedWrite("_rules/security.md", "Security rules", "Validate input.", "page.edit",
                "from session review");
    }

    private String reviewStatus(SessionId session) {
        List<String> s = jdbc.queryForList(
                "SELECT status FROM auto_improve_session_review WHERE session_id = ?",
                String.class, session.value());
        return s.isEmpty() ? null : s.get(0);
    }

    private int reviewAttempts(SessionId session) {
        return jdbc.queryForObject(
                "SELECT attempts FROM auto_improve_session_review WHERE session_id = ?",
                Integer.class, session.value());
    }

    private int watermarkCount(Seed seed) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auto_improve_watermark WHERE workspace = ? AND project = ?",
                Integer.class, seed.ws(), seed.proj());
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void tickReviewsDueSessionAndAppliesProposals() {
        Seed seed = freshScope();
        // Finished after the watermark this tick establishes (now()): a future ended_at is due.
        SessionId session = seedFinishedSession(seed, Instant.now().plusSeconds(3600));
        AutoImproveProperties props = props(3, 20);

        ProposalSource source = (scope, s) -> List.of(ruleEdit());
        new AutoImproveScheduler(state, gate(props), () -> java.util.List.of(source), props).tick();

        assertThat(applied).hasSize(1);
        assertThat(reviewStatus(session)).isEqualTo("done");
        List<PendingWriteRecord> report = pending.recent(seed.scope(), 10);
        assertThat(report).hasSize(1);
        assertThat(report.get(0).status()).isEqualTo(PendingWriteStatus.APPLIED);
    }

    @Test
    void withoutAProposalSourceTickBurnsNothing() {
        Seed seed = freshScope();
        SessionId session = seedFinishedSession(seed, Instant.now().plusSeconds(3600));
        AutoImproveProperties props = props(3, 20);

        // No engine wired (deferred #29/#19): the tick must not establish watermarks or claim sessions.
        new AutoImproveScheduler(state, gate(props), () -> null, props).tick();

        assertThat(watermarkCount(seed)).isZero();
        assertThat(reviewStatus(session)).isNull(); // never claimed
        assertThat(pending.recent(seed.scope(), 10)).isEmpty();
    }

    @Test
    void tickDoesNotOverlapItself() {
        Seed seed = freshScope();
        SessionId session = seedFinishedSession(seed, Instant.now().plusSeconds(3600));
        AutoImproveProperties props = props(3, 20);

        AtomicInteger sourceCalls = new AtomicInteger();
        AutoImproveScheduler[] holder = new AutoImproveScheduler[1];
        ProposalSource source = (scope, s) -> {
            sourceCalls.incrementAndGet();
            holder[0].tick(); // re-enter: must be a no-op while the outer tick holds the guard
            return List.of(ruleEdit());
        };
        holder[0] = new AutoImproveScheduler(state, gate(props), () -> java.util.List.of(source), props);
        holder[0].tick();

        assertThat(sourceCalls.get()).isEqualTo(1); // the re-entrant tick did not process again
        assertThat(reviewStatus(session)).isEqualTo("done");
        assertThat(applied).hasSize(1);
    }

    @Test
    void firstSightDoesNotRetroReviewHistory() {
        Seed seed = freshScope();
        // Finished in the past, before this project ever had a watermark: must NOT be retro-reviewed.
        SessionId session = seedFinishedSession(seed, Instant.now().minusSeconds(3600));
        AutoImproveProperties props = props(3, 20);

        AtomicInteger sourceCalls = new AtomicInteger();
        ProposalSource source = (scope, s) -> {
            sourceCalls.incrementAndGet();
            return List.of(ruleEdit());
        };
        new AutoImproveScheduler(state, gate(props), () -> java.util.List.of(source), props).tick();

        assertThat(watermarkCount(seed)).isEqualTo(1); // watermark established
        assertThat(sourceCalls.get()).isZero(); // but nothing before it reviewed
        assertThat(reviewStatus(session)).isNull();
        assertThat(pending.recent(seed.scope(), 10)).isEmpty();
    }

    @Test
    void failingReviewIsRecordedFailedAndRetriedOnlyUnderCap() {
        Seed seed = freshScope();
        SessionId session = seedFinishedSession(seed, Instant.now().plusSeconds(3600));
        AutoImproveProperties props = props(2, 20); // cap at 2 attempts

        AtomicInteger sourceCalls = new AtomicInteger();
        ProposalSource boom = (scope, s) -> {
            sourceCalls.incrementAndGet();
            throw new RuntimeException("review blew up");
        };
        AutoImproveScheduler scheduler = new AutoImproveScheduler(state, gate(props), () -> java.util.List.of(boom), props);

        scheduler.tick();
        assertThat(reviewStatus(session)).isEqualTo("failed");
        assertThat(reviewAttempts(session)).isEqualTo(1);

        scheduler.tick(); // retried (failed, under cap)
        assertThat(reviewAttempts(session)).isEqualTo(2);

        scheduler.tick(); // cap reached → not re-fed
        assertThat(sourceCalls.get()).isEqualTo(2);
        assertThat(reviewStatus(session)).isEqualTo("failed");
        assertThat(reviewAttempts(session)).isEqualTo(2);
    }

    @Test
    void sourcesKeepIndependentClaims() {
        Seed seed = freshScope();
        SessionId session = seedFinishedSession(seed, Instant.now().plusSeconds(3600));
        AutoImproveProperties props = props(3, 20);

        // The rule-based curator (default source id) reviews the session and marks it done...
        ProposalSource curator = (scope, s) -> List.of(ruleEdit());
        new AutoImproveScheduler(state, gate(props), () -> List.of(curator), props).tick();
        assertThat(applied).hasSize(1);

        // ...which must NOT hide it from the LLM review engine (#150, V16): its own claim namespace
        // still sees the session as due and reviews it independently.
        List<SessionId> reviewed = new ArrayList<>();
        ProposalSource review = new ProposalSource() {
            @Override
            public List<ProposedWrite> proposalsFor(Scope scope, SessionId s) {
                reviewed.add(s);
                return List.of();
            }

            @Override
            public String id() {
                return "review";
            }
        };
        new AutoImproveScheduler(state, gate(props), () -> List.of(review), props).tick();
        assertThat(reviewed).containsExactly(session);
    }

    @Test
    void aNotReadySourceConsumesNoClaims() {
        Seed seed = freshScope();
        SessionId session = seedFinishedSession(seed, Instant.now().plusSeconds(3600));
        AutoImproveProperties props = props(3, 20);

        // ready()=false is deferral, not failure (#148/#150): nothing is claimed, no attempt is
        // burned, and the session stays due for a later tick.
        ProposalSource paused = new ProposalSource() {
            @Override
            public List<ProposedWrite> proposalsFor(Scope scope, SessionId s) {
                throw new AssertionError("must not review while not ready");
            }

            @Override
            public boolean ready(int backlog) {
                return false;
            }
        };
        new AutoImproveScheduler(state, gate(props), () -> List.of(paused), props).tick();
        assertThat(reviewStatus(session)).isNull();

        ProposalSource open = (scope, s) -> List.of(ruleEdit());
        new AutoImproveScheduler(state, gate(props), () -> List.of(open), props).tick();
        assertThat(reviewStatus(session)).isEqualTo("done");
    }

    @Test
    void endTickAggregatesAreSubmittedThroughTheGate() {
        Seed seed = freshScope();
        seedFinishedSession(seed, Instant.now().plusSeconds(3600));
        AutoImproveProperties props = props(3, 20);

        // The review engine's tick report flows through the same gate, with no owning session.
        ProposalSource reporting = new ProposalSource() {
            @Override
            public List<ProposedWrite> proposalsFor(Scope scope, SessionId s) {
                return List.of();
            }

            @Override
            public List<ScopedWrite> endTick() {
                return List.of(new ScopedWrite(seed.scope(), ruleEdit()));
            }
        };
        new AutoImproveScheduler(state, gate(props), () -> List.of(reporting), props).tick();

        assertThat(applied).hasSize(1);
        List<PendingWriteRecord> report = pending.recent(seed.scope(), 10);
        assertThat(report).hasSize(1);
        assertThat(report.get(0).status()).isEqualTo(PendingWriteStatus.APPLIED);
    }

    @Test
    void reviewEngineEndToEndStagesDurableKnowledgeFromARealSession() {
        // AC1 of #150 over the real wiring: real scheduler + real state repo (V16) + real gate, with
        // the REAL SessionReviewProposalSource — only the LLM, observations and pages are doubles.
        Seed seed = freshScope();
        SessionId session = seedFinishedSession(seed, Instant.now().plusSeconds(3600));
        AutoImproveProperties props = new AutoImproveProperties(false, 3, 20,
                new AutoImproveProperties.Scheduler(false, null), null,
                new AutoImproveProperties.Review(true, 30, 5, 0.8, "", 50, null));

        SessionObservationReader reader = new SessionObservationReader() {
            @Override
            public Optional<Session> findSession(SessionId sessionId) {
                return Optional.empty();
            }

            @Override
            public List<Observation> observationsFor(SessionId sessionId) {
                List<Observation> out = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    out.add(new Observation(
                            new ObservationId(Uuid7.randomUuid()), sessionId,
                            Identity.ofProject(WorkspaceId.of(seed.ws()), ProjectId.of(seed.proj())),
                            ObservationKind.USER_PROMPT, ObservationKind.USER_PROMPT.wire(), null,
                            "substantial work happened here", Instant.parse("2026-07-01T12:00:00Z")));
                }
                return out;
            }

            @Override
            public int countFor(SessionId sessionId) {
                return 6;
            }
        };
        LlmProvider llm = new LlmProvider() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return new ChatResponse(
                        "{\"proposals\":[{\"kind\":\"decision\",\"title\":\"Ticks use fixed delay\","
                                + "\"body\":\"Fixed delay so ticks never overlap.\",\"confidence\":0.95,"
                                + "\"evidence\":\"stated in the session\"}]}",
                        "stub", "stub-model", 0, 0);
            }

            @Override
            public String id() {
                return "stub";
            }

            @Override
            public String model() {
                return "stub-model";
            }

            @Override
            public void probe() {}
        };
        PageRepository noPages = new PageRepository() {
            @Override
            public PageRecord create(Identity identity, String title, String body) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PageRecord create(Identity identity, String title, String body, PageWriteCallback cb) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<PageRecord> readLatest(Identity identity) {
                return Optional.empty();
            }

            @Override
            public List<PageRecord> listLatest(WorkspaceId workspace, ProjectId project) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<PageRecord> findById(PageId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<PageRecord> reinforce(PageId id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int dropWorkingFromLatest(WorkspaceId workspace, ProjectId project) {
                throw new UnsupportedOperationException();
            }
        };
        SessionReviewProposalSource review = new SessionReviewProposalSource(
                reader, noPages, llm, () -> null, new LlmRateLimitGate(), props);

        new AutoImproveScheduler(state, gate(props), () -> List.of(review), props).tick();

        // The durable page was auto-applied through the gate with the session link on the audit row.
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).path()).isEqualTo("decisions/ticks-use-fixed-delay.md");
        assertThat(applied.get(0).body()).contains("Fixed delay").contains(session.value().toString());
        assertThat(reviewStatus(session)).isEqualTo("done");
        List<PendingWriteRecord> rows = pending.recent(seed.scope(), 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo(PendingWriteStatus.APPLIED);
    }
}
