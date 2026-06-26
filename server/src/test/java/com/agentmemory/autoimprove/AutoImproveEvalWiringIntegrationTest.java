package com.agentmemory.autoimprove;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.SessionId;
import com.agentmemory.eval.EvalGate;
import com.agentmemory.eval.EvalGateProbe;
import com.agentmemory.eval.EvalGateProperties;
import com.agentmemory.recall.Scope;
import java.nio.file.Path;
import java.time.Duration;
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
 * Proves the issue #31 {@link EvalGate} is wired <strong>end-to-end</strong> into the auto-improve gate
 * (issue #30), not left as a dormant seam: with the eval gate switched ON (a real subprocess — the
 * portable {@link EvalGateProbe}, the same fixture {@code EvalGateTest} uses), a proposal flows
 * propose → gate → eval → apply/reject, and the verdict is recorded in {@code pending_writes.eval_result}.
 *
 * <p>Covers a passing gate (applied + PASSED verdict), a blocking gate (rejected, never applied, BLOCKED
 * verdict), prefix selection (a non-matching path is SKIPPED and applies with no verdict), and the
 * human-approval path being eval-gated too. The {@link ProposalApplier} is a fake — this test is about the
 * eval wiring, not the durable write stack.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class AutoImproveEvalWiringIntegrationTest {

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

    @Autowired
    DataSource dataSource;

    private JdbcPendingWriteRepository pending;
    private final List<ProposedWrite> applied = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pending = new JdbcPendingWriteRepository(new JdbcTemplate(dataSource));
        applied.clear();
    }

    /** A gate whose eval step runs the probe in {@code mode}, applying only to {@code _rules/} paths. */
    private AutoImproveGate gateWithEval(String mode, boolean requireApproval) {
        EvalGate eval = new EvalGate(new EvalGateProperties(
                true, List.of("_rules/"), probe(mode), Duration.ofSeconds(30), 65_536, null));
        AutoImproveProperties props = new AutoImproveProperties(
                requireApproval, 3, 20, new AutoImproveProperties.Scheduler(false, null), null);
        return new AutoImproveGate(pending, (scope, write) -> applied.add(write), props, eval);
    }

    private static Scope freshScope() {
        return Scope.of("ws" + UUID.randomUUID().toString().replace("-", ""), "proj");
    }

    private static ProposedWrite ruleEdit() {
        return new ProposedWrite("_rules/security.md", "Security rules", "Validate input.", "page.edit",
                "tightening input validation");
    }

    @Test
    @Timeout(60)
    void aPassingEvalAppliesAndRecordsThePassedVerdict() {
        Scope scope = freshScope();
        AutoImproveGate.Decision d = gateWithEval("pass", false).submit(scope, SessionId.newId(), ruleEdit());

        assertThat(d.applied()).isTrue();
        assertThat(applied).hasSize(1); // the write path WAS taken
        PendingWriteRecord rec = pending.findById(d.id()).orElseThrow();
        assertThat(rec.status()).isEqualTo(PendingWriteStatus.APPLIED);
        assertThat(rec.evalResult()).contains("PASSED").contains("ok"); // verdict recorded in the audit slot
    }

    @Test
    @Timeout(60)
    void aBlockingEvalRejectsWithoutApplyingAndRecordsTheBlockedVerdict() {
        Scope scope = freshScope();
        AutoImproveGate.Decision d = gateWithEval("fail", false).submit(scope, SessionId.newId(), ruleEdit());

        assertThat(d.rejected()).isTrue();
        assertThat(applied).isEmpty(); // fail-closed: the write path was NOT taken
        PendingWriteRecord rec = pending.findById(d.id()).orElseThrow();
        assertThat(rec.status()).isEqualTo(PendingWriteStatus.REJECTED);
        assertThat(rec.appliedAt()).isNull();
        assertThat(rec.evalResult()).contains("BLOCKED").contains("rule violated");
    }

    @Test
    @Timeout(60)
    void aPathOutsideTheConfiguredPrefixesIsSkippedAndApplies() {
        Scope scope = freshScope();
        ProposedWrite conceptEdit = new ProposedWrite(
                "concepts/recall.md", "Recall", "How recall works.", "page.edit", null);
        AutoImproveGate.Decision d = gateWithEval("fail", false).submit(scope, SessionId.newId(), conceptEdit);

        // The blocking probe would reject — but concepts/ is outside the _rules/ prefix, so the gate is
        // SKIPPED and the proposal applies, with no verdict recorded.
        assertThat(d.applied()).isTrue();
        assertThat(applied).hasSize(1);
        assertThat(pending.findById(d.id()).orElseThrow().evalResult()).isNull();
    }

    @Test
    @Timeout(60)
    void approveIsEvalGatedToo() {
        Scope scope = freshScope();
        AutoImproveGate gate = gateWithEval("fail", true); // require approval + a blocking gate
        AutoImproveGate.Decision held = gate.submit(scope, SessionId.newId(), ruleEdit());
        assertThat(held.held()).isTrue();
        assertThat(applied).isEmpty();

        PendingWriteRecord afterApprove = gate.approve(held.id());

        // The human approved, but the eval gate still blocks the apply (fail-closed).
        assertThat(afterApprove.status()).isEqualTo(PendingWriteStatus.REJECTED);
        assertThat(applied).isEmpty();
        assertThat(afterApprove.evalResult()).contains("BLOCKED");
    }
}
