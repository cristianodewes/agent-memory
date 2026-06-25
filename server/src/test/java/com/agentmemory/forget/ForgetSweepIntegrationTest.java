package com.agentmemory.forget;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
 * End-to-end coverage of the forget sweep (issue #25) against a throwaway {@code pgvector/pgvector:pg16}
 * Postgres (Testcontainers): cold pages are soft-deleted; semantic / slot / recently-accessed pages
 * are exempt; {@code dry_run} previews without mutating; a soft-delete is recoverable; an aged
 * soft-delete is purged (row + audited). Pages are seeded with raw SQL so each test pins the exact
 * layer / access / age it needs. The offline {@code test} provider boots the context (DD-005).
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    // Make the cold threshold easy to trigger and the recency window small for the tests.
    "agent-memory.decay.cold-threshold=0.05",
    "agent-memory.decay.hard-delete-after-days=30",
    "agent-memory.decay.recently-accessed-days=7"
})
@Testcontainers
class ForgetSweepIntegrationTest {

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

    @Autowired ForgetSweepService sweep;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- seeding -----------------------------------------------------------------------------------

    private record Proj(WorkspaceId ws, ProjectId proj, String wsSlug) {}

    private Proj freshProject() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, "proj");
        return new Proj(WorkspaceId.of(ws), ProjectId.of("proj"), ws);
    }

    /**
     * Insert one latest page with a chosen layer / age / access so its retention score is controlled.
     * A very old created_at + zero access drives an age-decaying layer cold.
     */
    private UUID seedPage(Proj p, String path, String layer, Instant createdAt,
            long accessCount, Instant lastAccessedAt) {
        UUID wsId = jdbc().queryForObject(
                "SELECT id FROM workspaces WHERE slug = ?", UUID.class, p.wsSlug());
        UUID projId = jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?", UUID.class, p.wsSlug(), "proj");
        UUID id = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, body, "
                        + "is_latest, layer, access_count, last_accessed_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'proj', ?, ?, 'body', true, ?, ?, ?, ?, ?)",
                id, wsId, projId, p.wsSlug(), path, "t", layer, accessCount,
                lastAccessedAt == null ? null : java.sql.Timestamp.from(lastAccessedAt),
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
        return id;
    }

    private static final Instant LONG_AGO = Instant.now().minus(800, ChronoUnit.DAYS);

    private boolean isLatest(UUID id) {
        return Boolean.TRUE.equals(jdbc().queryForObject(
                "SELECT is_latest FROM pages WHERE id = ?", Boolean.class, id));
    }

    private boolean exists(UUID id) {
        return jdbc().queryForObject("SELECT count(*) FROM pages WHERE id = ?", Integer.class, id) > 0;
    }

    private Instant deletedAt(UUID id) {
        var ts = jdbc().queryForObject(
                "SELECT deleted_at FROM pages WHERE id = ?", java.sql.Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    // --- soft-delete of cold pages -----------------------------------------------------------------

    @Test
    void coldEpisodicPageIsSoftDeleted() {
        Proj p = freshProject();
        UUID cold = seedPage(p, "sessions/old.md", "episodic", LONG_AGO, 0, null);

        SweepReport report = sweep.sweep(p.ws(), p.proj(), false);

        assertThat(report.dryRun()).isFalse();
        assertThat(report.softDeleted()).extracting(SweepCandidate::path).contains("sessions/old.md");
        // The row is no longer latest and carries a deleted_at — recoverable, not gone.
        assertThat(isLatest(cold)).isFalse();
        assertThat(deletedAt(cold)).isNotNull();
        assertThat(exists(cold)).isTrue();
    }

    @Test
    void warmPageIsNotSwept() {
        Proj p = freshProject();
        // Fresh, recently created episodic page scores well above the cold threshold.
        UUID warm = seedPage(p, "sessions/fresh.md", "episodic", Instant.now(), 0, null);

        SweepReport report = sweep.sweep(p.ws(), p.proj(), false);

        assertThat(report.softDeleted()).extracting(SweepCandidate::path).doesNotContain("sessions/fresh.md");
        assertThat(isLatest(warm)).isTrue();
        assertThat(deletedAt(warm)).isNull();
    }

    // --- exemptions --------------------------------------------------------------------------------

    @Test
    void semanticAndSlotAndRecentlyAccessedAreExempt() {
        Proj p = freshProject();
        // Three pages that an age-only sweep would want to evict, each protected for a different reason.
        UUID semantic = seedPage(p, "concepts/durable.md", "semantic", LONG_AGO, 0, null);
        UUID slot = seedPage(p, "_slots/state.md", "episodic", LONG_AGO, 0, null);
        UUID recent = seedPage(p, "sessions/recent.md", "episodic", LONG_AGO, 0,
                Instant.now().minus(1, ChronoUnit.DAYS)); // accessed yesterday < 7d window

        SweepReport report = sweep.sweep(p.ws(), p.proj(), false);

        // The user-facing guarantee: none of the three is swept.
        assertThat(report.softDeleted()).isEmpty();
        assertThat(isLatest(semantic)).isTrue();
        assertThat(isLatest(slot)).isTrue();
        assertThat(isLatest(recent)).isTrue();
        // The slot and recently-accessed pages ARE cold (episodic, 800d old) but spared by an
        // exemption, so they are counted as exempt-skipped. The semantic page is spared upstream — the
        // shared retention math (#24) does not age the semantic layer out, so it never even scores
        // cold and so is never a sweep candidate to begin with (a stronger guarantee than exemption).
        assertThat(report.exemptSkipped()).isEqualTo(2);
    }

    // --- dry run -----------------------------------------------------------------------------------

    @Test
    void dryRunPreviewsButMutatesNothing() {
        Proj p = freshProject();
        UUID cold = seedPage(p, "sessions/old.md", "episodic", LONG_AGO, 0, null);

        SweepReport preview = sweep.sweep(p.ws(), p.proj(), true);

        assertThat(preview.dryRun()).isTrue();
        assertThat(preview.softDeleted()).extracting(SweepCandidate::path).contains("sessions/old.md");
        // Nothing changed: still latest, no deleted_at.
        assertThat(isLatest(cold)).isTrue();
        assertThat(deletedAt(cold)).isNull();

        // And a real run then matches what the preview promised.
        SweepReport applied = sweep.sweep(p.ws(), p.proj(), false);
        assertThat(applied.softDeletedCount()).isEqualTo(preview.softDeletedCount());
        assertThat(isLatest(cold)).isFalse();
    }

    // --- purge of an aged soft-delete --------------------------------------------------------------

    @Test
    void agedSoftDeleteIsPurgedAndAudited() {
        Proj p = freshProject();
        // Seed a page already soft-deleted 60 days ago (older than hard-delete-after-days=30), not
        // accessed since — directly, to exercise the purge stage without waiting.
        UUID purgeable = seedPage(p, "sessions/ancient.md", "episodic", LONG_AGO, 0, null);
        Instant deleted60dAgo = Instant.now().minus(60, ChronoUnit.DAYS);
        jdbc().update("UPDATE pages SET is_latest = false, deleted_at = ? WHERE id = ?",
                java.sql.Timestamp.from(deleted60dAgo), purgeable);

        long auditBefore = auditCount(p);
        SweepReport report = sweep.sweep(p.ws(), p.proj(), false);

        assertThat(report.purged()).extracting(SweepCandidate::path).contains("sessions/ancient.md");
        assertThat(exists(purgeable)).isFalse(); // hard-deleted
        // The sweep recorded an audit row (action forget.sweep) for this project.
        assertThat(auditCount(p)).isGreaterThan(auditBefore);
        Integer sweepAudits = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log WHERE workspace = ? AND project = 'proj' "
                        + "AND action = 'forget.sweep'",
                Integer.class, p.wsSlug());
        assertThat(sweepAudits).isGreaterThanOrEqualTo(1);
    }

    @Test
    void freshSoftDeleteIsNotPurged() {
        Proj p = freshProject();
        // Soft-deleted just now: within the recovery window, so a sweep must NOT purge it.
        UUID recent = seedPage(p, "sessions/justdeleted.md", "episodic", LONG_AGO, 0, null);
        jdbc().update("UPDATE pages SET is_latest = false, deleted_at = now() WHERE id = ?", recent);

        SweepReport report = sweep.sweep(p.ws(), p.proj(), false);

        assertThat(report.purged()).extracting(SweepCandidate::path).doesNotContain("sessions/justdeleted.md");
        assertThat(exists(recent)).isTrue(); // still recoverable
    }

    private long auditCount(Proj p) {
        Long n = jdbc().queryForObject(
                "SELECT count(*) FROM audit_log WHERE workspace = ? AND project = 'proj'",
                Long.class, p.wsSlug());
        return n == null ? 0 : n;
    }
}
