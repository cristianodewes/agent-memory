package com.agentmemory.autoimprove;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.consolidate.Consolidator;
import com.agentmemory.consolidate.SessionObservationReader;
import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.eval.EvalGate;
import com.agentmemory.eval.EvalGateProbe;
import com.agentmemory.eval.EvalGateProperties;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.mcp.McpReadRepository;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
 * Proves the production {@link ProposalSource} — the {@link ConsolidationProposalSource} over the #19
 * {@link Consolidator} in propose-only mode (issue #30) — against a real {@code pgvector/pgvector:pg16}.
 *
 * <p>It exercises the <strong>real</strong> path end-to-end, not a dormant seam: a session's distilled pages
 * are <em>proposed</em> (the consolidator is driven by a scripted {@link TestDoubleProvider}, so the LLM
 * reply is deterministic while the propose-only distillation runs for real) <strong>without being
 * written</strong>, then flow through the approval {@link AutoImproveGate} and the #31 {@link EvalGate} (a
 * real subprocess — the portable {@link EvalGateProbe}) into {@code pending_writes}. A {@code procedures/}
 * page matches the eval prefix and is gated (verdict recorded in {@code eval_result}); a {@code concepts/}
 * page is outside the prefix and is {@code SKIPPED}. Covers a passing gate (gated page applied + PASSED) and
 * a blocking gate (gated page rejected + BLOCKED, skipped page still applied).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class ConsolidationProposalSourceIntegrationTest {

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

    // The portable JVM probe stands in for a project's external eval command (Windows/Linux safe).
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    private static final String CLASSPATH = codeSource();
    private static final String PROBE = EvalGateProbe.class.getName();

    private static String codeSource() {
        try {
            return Path.of(EvalGateProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (Exception e) {
            return System.getProperty("java.class.path");
        }
    }

    private static List<String> probe(String mode) {
        return List.of(JAVA, "-cp", CLASSPATH, PROBE, mode);
    }

    @Autowired MemoryWriteService writes;
    @Autowired McpReadRepository reads;
    @Autowired SessionObservationReader reader;
    @Autowired PageRepository pages;
    @Autowired DataSource dataSource;
    @Autowired ProposalApplier applier; // the production applier (→ MemoryWriteService), for the real-write test

    private JdbcPendingWriteRepository pending;
    private final List<ProposedWrite> applied = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pending = new JdbcPendingWriteRepository(new JdbcTemplate(dataSource));
        applied.clear();
    }

    // --- seeding -----------------------------------------------------------------------------------

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private record Proj(String ws, String proj, UUID wsId, UUID projId) {
        Scope scope() {
            return Scope.of(ws, proj);
        }
    }

    private Proj freshProject() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return new Proj(ws, proj, wsId, projId);
    }

    private SessionId seedSessionWithObservations(Proj p, String... prompts) {
        SessionId sid = SessionId.newId();
        jdbc().update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sid.value(), p.wsId(), p.projId(), p.ws(), p.proj(),
                java.sql.Timestamp.from(Instant.now()));
        for (String prompt : prompts) {
            jdbc().update(
                    "INSERT INTO observations (id, session_id, workspace_id, project_id, workspace, "
                            + "project, kind, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), sid.value(), p.wsId(), p.projId(), p.ws(), p.proj(),
                    "user-prompt", prompt, java.sql.Timestamp.from(Instant.now()));
        }
        return sid;
    }

    private Identity pageId(Proj p, String path) {
        return Identity.ofPage(WorkspaceId.of(p.ws()), ProjectId.of(p.proj()), PagePath.of(path));
    }

    /** A {@link Consolidator} backed by a scripted LLM (returning {@code cannedJson}) over the real services. */
    private Consolidator consolidatorReturning(String cannedJson) {
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> cannedJson)
                .build();
        return new Consolidator(reader, llm, writes, reads);
    }

    private static String page(String folder, String slug, String title, String body) {
        return "{\"folder\":\"" + folder + "\",\"slug\":\"" + slug + "\",\"title\":\"" + title
                + "\",\"body\":\"" + body + "\"}";
    }

    private static String pagesJson(String... pageObjects) {
        return "{\"pages\":[" + String.join(",", pageObjects) + "]}";
    }

    /** A gate whose eval step runs the probe in {@code mode}, applying only to {@code procedures/} paths. */
    private AutoImproveGate gateWithEval(String mode) {
        EvalGate eval = new EvalGate(new EvalGateProperties(
                true, List.of("procedures/"), probe(mode), Duration.ofSeconds(30), 65_536, null));
        AutoImproveProperties props = new AutoImproveProperties(
                false, 3, 20, new AutoImproveProperties.Scheduler(false, null));
        return new AutoImproveGate(pending, (scope, write) -> applied.add(write), props, eval);
    }

    private PendingWriteRecord recordByPath(List<AutoImproveGate.Decision> decisions, String path) {
        for (AutoImproveGate.Decision d : decisions) {
            PendingWriteRecord rec = pending.findById(d.id()).orElseThrow();
            if (rec.path().equals(path)) {
                return rec;
            }
        }
        throw new AssertionError("no proposal recorded for path " + path);
    }

    private List<AutoImproveGate.Decision> reviewThrough(
            AutoImproveGate gate, Scope scope, SessionId session, List<ProposedWrite> proposals) {
        List<AutoImproveGate.Decision> decisions = new ArrayList<>();
        for (ProposedWrite w : proposals) {
            decisions.add(gate.submit(scope, session, w)); // mirrors AutoImproveScheduler.reviewOne
        }
        return decisions;
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void proposesEachConsolidatedPageWithoutWriting() {
        Proj p = freshProject();
        SessionId session = seedSessionWithObservations(p, "deployed the service and explained recall");
        Consolidator consolidator = consolidatorReturning(pagesJson(
                page("procedures", "deploy", "Deploy", "Run ./deploy.sh then verify."),
                page("concepts", "recall", "Recall", "RRF fuses full-text and graph.")));

        List<ProposedWrite> proposals =
                new ConsolidationProposalSource(consolidator, true).proposalsFor(p.scope(), session);

        // Each consolidated page becomes one ProposedWrite (folder/slug.md), in order.
        assertThat(proposals).extracting(ProposedWrite::path)
                .containsExactly("procedures/deploy.md", "concepts/recall.md");
        assertThat(proposals).allSatisfy(w -> {
            assertThat(w.kind()).isEqualTo("page.edit");
            assertThat(w.rationale()).contains(session.value().toString());
        });
        assertThat(proposals.get(0).title()).isEqualTo("Deploy");
        assertThat(proposals.get(0).body()).contains("deploy.sh");

        // Propose-only: the consolidator did NOT write either page — they are staged for approval, not committed.
        assertThat(pages.readLatest(pageId(p, "procedures/deploy.md"))).isEmpty();
        assertThat(pages.readLatest(pageId(p, "concepts/recall.md"))).isEmpty();
    }

    @Test
    void aSessionWithNoObservationsProposesNothing() {
        Proj p = freshProject();
        SessionId empty = SessionId.newId(); // no observations seeded for it
        Consolidator consolidator = consolidatorReturning(pagesJson(page("concepts", "x", "X", "y")));

        assertThat(new ConsolidationProposalSource(consolidator, true).proposalsFor(p.scope(), empty))
                .isEmpty();
    }

    @Test
    @Timeout(120)
    void consolidationProposalsFlowThroughTheEvalGateAndApply() {
        Proj p = freshProject();
        SessionId session = seedSessionWithObservations(p, "deploy steps + recall design");
        Consolidator consolidator = consolidatorReturning(pagesJson(
                page("procedures", "deploy", "Deploy", "Run ./deploy.sh then verify."),
                page("concepts", "recall", "Recall", "RRF fuses signals.")));

        List<ProposedWrite> proposals =
                new ConsolidationProposalSource(consolidator, true).proposalsFor(p.scope(), session);

        List<AutoImproveGate.Decision> decisions =
                reviewThrough(gateWithEval("pass"), p.scope(), session, proposals);

        // procedures/ is in the eval prefix → gated, passes, applies, and the PASSED verdict is recorded.
        PendingWriteRecord proc = recordByPath(decisions, "procedures/deploy.md");
        assertThat(proc.status()).isEqualTo(PendingWriteStatus.APPLIED);
        assertThat(proc.evalResult()).contains("PASSED");
        // concepts/ is outside the eval prefix → SKIPPED, applies, no verdict recorded.
        PendingWriteRecord concept = recordByPath(decisions, "concepts/recall.md");
        assertThat(concept.status()).isEqualTo(PendingWriteStatus.APPLIED);
        assertThat(concept.evalResult()).isNull();
        // Both reached the real write path.
        assertThat(applied).extracting(ProposedWrite::path)
                .containsExactlyInAnyOrder("procedures/deploy.md", "concepts/recall.md");
    }

    @Test
    @Timeout(120)
    void aBlockingEvalRejectsTheGatedPageButSkippedPagesStillApply() {
        Proj p = freshProject();
        SessionId session = seedSessionWithObservations(p, "deploy steps + recall design");
        Consolidator consolidator = consolidatorReturning(pagesJson(
                page("procedures", "deploy", "Deploy", "Run ./deploy.sh then verify."),
                page("concepts", "recall", "Recall", "RRF fuses signals.")));

        List<ProposedWrite> proposals =
                new ConsolidationProposalSource(consolidator, true).proposalsFor(p.scope(), session);

        List<AutoImproveGate.Decision> decisions =
                reviewThrough(gateWithEval("fail"), p.scope(), session, proposals);

        // procedures/ is gated and the blocking probe rejects it (fail-closed): never applied, BLOCKED verdict.
        PendingWriteRecord proc = recordByPath(decisions, "procedures/deploy.md");
        assertThat(proc.status()).isEqualTo(PendingWriteStatus.REJECTED);
        assertThat(proc.appliedAt()).isNull();
        assertThat(proc.evalResult()).contains("BLOCKED");
        // concepts/ is outside the prefix → SKIPPED, so it still applies despite the blocking probe.
        PendingWriteRecord concept = recordByPath(decisions, "concepts/recall.md");
        assertThat(concept.status()).isEqualTo(PendingWriteStatus.APPLIED);
        assertThat(applied).extracting(ProposedWrite::path).containsExactly("concepts/recall.md");
    }

    @Test
    @Timeout(120)
    void aProposedConsolidationPageIsWrittenDurablyThroughTheRealApplyPath() {
        Proj p = freshProject();
        SessionId session = seedSessionWithObservations(p, "documented the deploy steps");
        Consolidator consolidator = consolidatorReturning(pagesJson(
                page("procedures", "deploy", "Deploy", "Run ./deploy.sh then verify.")));
        List<ProposedWrite> proposals =
                new ConsolidationProposalSource(consolidator, true).proposalsFor(p.scope(), session);
        assertThat(proposals).hasSize(1);

        // Real apply path: the production ProposalApplier (→ MemoryWriteService), eval gate ON for procedures/.
        EvalGate eval = new EvalGate(new EvalGateProperties(
                true, List.of("procedures/"), probe("pass"), Duration.ofSeconds(30), 65_536, null));
        AutoImproveProperties props = new AutoImproveProperties(
                false, 3, 20, new AutoImproveProperties.Scheduler(false, null));
        AutoImproveGate gate = new AutoImproveGate(pending, applier, props, eval);

        AutoImproveGate.Decision d = gate.submit(p.scope(), session, proposals.get(0));

        assertThat(d.applied()).isTrue();
        // The consolidated page is now a real durable page, written through MemoryWriteService (actor "auto-improve").
        assertThat(pages.readLatest(pageId(p, "procedures/deploy.md"))).isPresent();
        PendingWriteRecord rec = pending.findById(d.id()).orElseThrow();
        assertThat(rec.status()).isEqualTo(PendingWriteStatus.APPLIED);
        assertThat(rec.evalResult()).contains("PASSED");
    }
}
