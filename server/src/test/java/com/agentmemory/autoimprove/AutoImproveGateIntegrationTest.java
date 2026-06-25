package com.agentmemory.autoimprove;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * Integration coverage for the auto-improve data + gate layer (issue #30) against a real
 * {@code pgvector/pgvector:pg16} with the Flyway migrations applied (the V8 {@code pending_writes} plus
 * the V14 watermark/claims). Proves the approval gate (hold vs. apply through a fake
 * {@link ProposalApplier}), the first-run watermark (established once), and per-session claims (de-dupe +
 * attempt cap). The LLM provider is the offline {@code test} double so the startup gate passes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class AutoImproveGateIntegrationTest {

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

    private JdbcPendingWriteRepository pending;
    private JdbcAutoImproveStateRepository state;

    /** A fake write path that just records what it was asked to apply. */
    private final List<ProposedWrite> applied = new ArrayList<>();

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        pending = new JdbcPendingWriteRepository(jdbc);
        state = new JdbcAutoImproveStateRepository(jdbc);
        applied.clear();
    }

    private AutoImproveGate gate(boolean requireApproval) {
        AutoImproveProperties props = new AutoImproveProperties(
                requireApproval, 3, 20, new AutoImproveProperties.Scheduler(false, null));
        return new AutoImproveGate(pending, (scope, write) -> applied.add(write), props);
    }

    private static Scope freshScope() {
        return Scope.of("ws" + UUID.randomUUID().toString().replace("-", ""), "proj");
    }

    private static ProposedWrite ruleEdit() {
        return new ProposedWrite("_rules/security.md", "Security rules", "Validate input.", "page.edit",
                "tightening input validation");
    }

    @Test
    void defaultAppliesThroughTheWritePathAndRecordsApplied() {
        Scope scope = freshScope();
        AutoImproveGate.Decision d = gate(false).submit(scope, SessionId.newId(), ruleEdit());

        assertThat(d.applied()).isTrue();
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).path()).isEqualTo("_rules/security.md");

        List<PendingWriteRecord> report = pending.recent(scope, 10);
        assertThat(report).hasSize(1);
        assertThat(report.get(0).status()).isEqualTo(PendingWriteStatus.APPLIED);
        assertThat(report.get(0).appliedAt()).isNotNull();
        assertThat(report.get(0).proposal()).contains("_rules/security.md").contains("Validate input.");
    }

    @Test
    void requireApprovalHoldsTheProposalUnapplied() {
        Scope scope = freshScope();
        AutoImproveGate.Decision d = gate(true).submit(scope, SessionId.newId(), ruleEdit());

        assertThat(d.held()).isTrue();
        assertThat(applied).isEmpty(); // the write path was NOT taken
        List<PendingWriteRecord> report = pending.recent(scope, 10);
        assertThat(report).hasSize(1);
        assertThat(report.get(0).status()).isEqualTo(PendingWriteStatus.PROPOSED);
        assertThat(report.get(0).appliedAt()).isNull();
    }

    @Test
    void approveAppliesAHeldProposalThroughTheWritePath() {
        Scope scope = freshScope();
        AutoImproveGate g = gate(true);
        AutoImproveGate.Decision held = g.submit(scope, SessionId.newId(), ruleEdit());
        assertThat(applied).isEmpty(); // held, not yet applied

        PendingWriteRecord rec = g.approve(held.id());

        assertThat(rec.status()).isEqualTo(PendingWriteStatus.APPLIED);
        assertThat(rec.appliedAt()).isNotNull();
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).path()).isEqualTo("_rules/security.md");
        assertThat(applied.get(0).body()).isEqualTo("Validate input."); // rebuilt from the stored proposal
    }

    @Test
    void rejectDiscardsAHeldProposalWithoutWriting() {
        Scope scope = freshScope();
        AutoImproveGate g = gate(true);
        AutoImproveGate.Decision held = g.submit(scope, SessionId.newId(), ruleEdit());

        PendingWriteRecord rec = g.reject(held.id());

        assertThat(rec.status()).isEqualTo(PendingWriteStatus.REJECTED);
        assertThat(rec.decidedAt()).isNotNull();
        assertThat(rec.appliedAt()).isNull();
        assertThat(applied).isEmpty(); // never written
    }

    @Test
    void onlyAProposedRowCanBeApprovedOrRejected() {
        Scope scope = freshScope();
        AutoImproveGate g = gate(false); // default: applies immediately
        AutoImproveGate.Decision d = g.submit(scope, SessionId.newId(), ruleEdit());
        assertThat(d.applied()).isTrue();

        assertThatThrownBy(() -> g.approve(d.id())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> g.reject(d.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void approvingAnUnknownProposalFails() {
        assertThatThrownBy(() -> gate(true).approve(java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void watermarkIsEstablishedOnceAndStable() {
        Scope scope = freshScope();
        Instant first = state.watermark(scope);
        Instant second = state.watermark(scope);
        assertThat(second).isEqualTo(first); // not advanced on the second tick
    }

    @Test
    void claimDeDupesAndCapsAttempts() {
        Scope scope = freshScope();
        SessionId session = SessionId.newId();
        int maxAttempts = 2;

        assertThat(state.claim(scope, session, maxAttempts)).isTrue(); // attempt 1
        state.markFailed(session, "boom");
        assertThat(state.claim(scope, session, maxAttempts)).isTrue(); // attempt 2 (under cap)
        state.markFailed(session, "boom again");
        assertThat(state.claim(scope, session, maxAttempts)).isFalse(); // cap reached → no more retries
    }

    @Test
    void aDoneSessionIsNeverReclaimed() {
        Scope scope = freshScope();
        SessionId session = SessionId.newId();
        assertThat(state.claim(scope, session, 3)).isTrue();
        state.markDone(session);
        assertThat(state.claim(scope, session, 3)).isFalse(); // terminal
    }
}
