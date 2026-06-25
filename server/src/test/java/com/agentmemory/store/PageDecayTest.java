package com.agentmemory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * Exercises the layered-memory + decay behavior (issue #24) end-to-end against a throwaway
 * {@code pgvector/pgvector:pg16} Postgres (Testcontainers): a created page carries the layer its path
 * classifies into, an access reinforcement bump increments {@code access_count} / {@code
 * last_accessed_at} and raises the retention score, working-layer pages are dropped from latest at
 * session end (while their rows are retained), and the {@link RetentionScorer} bean ranks a
 * reinforced page above a stale one. The offline {@code test} provider boots the full context
 * (DD-005); this test is about decay, not the LLM.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class PageDecayTest {

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

    @Autowired PageRepository pages;
    @Autowired RetentionScorer scorer;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static WorkspaceId freshWorkspace() {
        // Unique workspace per call keeps tests isolated on the shared container.
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static Identity pageAt(WorkspaceId ws, String path) {
        return Identity.ofPage(ws, ProjectId.of("proj"), PagePath.of(path));
    }

    // --- layer classification persists ----------------------------------------------------------

    @Test
    void createClassifiesAndPersistsTheLayerFromThePath() {
        WorkspaceId ws = freshWorkspace();
        PageRecord concept = pages.create(pageAt(ws, "concepts/recall.md"), "t", "b");
        PageRecord session = pages.create(pageAt(ws, "sessions/2026-06-25.md"), "t", "b");
        PageRecord procedure = pages.create(pageAt(ws, "procedures/release.md"), "t", "b");
        PageRecord scratch = pages.create(pageAt(ws, "scratch/wip.md"), "t", "b");

        assertThat(concept.layer()).isEqualTo(MemoryLayer.SEMANTIC);
        assertThat(session.layer()).isEqualTo(MemoryLayer.EPISODIC);
        assertThat(procedure.layer()).isEqualTo(MemoryLayer.PROCEDURAL);
        assertThat(scratch.layer()).isEqualTo(MemoryLayer.WORKING);

        // Round-trips through a reload, and the column matches the classified wire value.
        PageRecord reloaded = pages.findById(concept.id()).orElseThrow();
        assertThat(reloaded.layer()).isEqualTo(MemoryLayer.SEMANTIC);
        String stored = jdbc().queryForObject(
                "SELECT layer FROM pages WHERE id = ?", String.class, concept.id().value());
        assertThat(stored).isEqualTo("semantic");
    }

    @Test
    void layerCheckConstraintRejectsAnUnknownValue() {
        WorkspaceId ws = freshWorkspace();
        PageRecord page = pages.create(pageAt(ws, "concepts/x.md"), "t", "b");
        // The DB-level enum guard (V11 CHECK) backs the application-side classification.
        assertThatThrownBy(() -> jdbc().update(
                "UPDATE pages SET layer = 'bogus' WHERE id = ?", page.id().value()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // --- access reinforcement -------------------------------------------------------------------

    @Test
    void reinforceBumpsAccessCountAndStampsLastAccessed() {
        WorkspaceId ws = freshWorkspace();
        PageRecord v1 = pages.create(pageAt(ws, "concepts/reinforced.md"), "t", "b");
        assertThat(v1.accessCount()).isZero();
        assertThat(v1.lastAccessedAt()).isNull();

        PageRecord afterFirst = pages.reinforce(v1.id()).orElseThrow();
        assertThat(afterFirst.accessCount()).isEqualTo(1);
        assertThat(afterFirst.lastAccessedAt()).isNotNull();

        PageRecord afterSecond = pages.reinforce(v1.id()).orElseThrow();
        assertThat(afterSecond.accessCount()).isEqualTo(2);
        // Persisted, not just returned.
        PageRecord reloaded = pages.findById(v1.id()).orElseThrow();
        assertThat(reloaded.accessCount()).isEqualTo(2);
        assertThat(reloaded.lastAccessedAt()).isNotNull();
    }

    @Test
    void reinforceMissingPageIsEmpty() {
        assertThat(pages.reinforce(PageId.newId())).isEmpty();
    }

    @Test
    void reinforcementRaisesTheRetentionScoreOfThePage() {
        WorkspaceId ws = freshWorkspace();
        PageRecord stale = pages.create(pageAt(ws, "sessions/stale.md"), "t", "b");
        PageRecord hot = pages.create(pageAt(ws, "sessions/hot.md"), "t", "b");

        // Reinforce one page several times; both are the same age (created moments apart).
        for (int i = 0; i < 5; i++) {
            hot = pages.reinforce(hot.id()).orElseThrow();
        }

        double staleScore = stale.retentionScore(scorer);
        double hotScore = hot.retentionScore(scorer);
        assertThat(hotScore).isGreaterThan(staleScore);
    }

    // --- working layer dropped from latest at session end ---------------------------------------

    @Test
    void dropWorkingFromLatestDemotesOnlyWorkingPagesButKeepsTheRows() {
        WorkspaceId ws = freshWorkspace();
        ProjectId proj = ProjectId.of("proj");
        PageRecord working = pages.create(pageAt(ws, "scratch/wip.md"), "t", "b");
        PageRecord episodic = pages.create(pageAt(ws, "sessions/keep.md"), "t", "b");
        PageRecord semantic = pages.create(pageAt(ws, "concepts/keep.md"), "t", "b");

        int demoted = pages.dropWorkingFromLatest(ws, proj);
        assertThat(demoted).isEqualTo(1);

        // Working page is no longer latest, but the row is retained (history / observations live on).
        assertThat(pages.readLatest(pageAt(ws, "scratch/wip.md"))).isEmpty();
        PageRecord stillThere = pages.findById(working.id()).orElseThrow();
        assertThat(stillThere.isLatest()).isFalse();

        // Other layers are untouched and still latest.
        assertThat(pages.readLatest(pageAt(ws, "sessions/keep.md"))).isPresent();
        assertThat(pages.readLatest(pageAt(ws, "concepts/keep.md"))).isPresent();
        List<PageRecord> latest = pages.listLatest(ws, proj);
        assertThat(latest).extracting(PageRecord::id)
                .containsExactlyInAnyOrder(episodic.id(), semantic.id());
    }

    @Test
    void dropWorkingFromLatestIsZeroWhenNoWorkingPages() {
        WorkspaceId ws = freshWorkspace();
        pages.create(pageAt(ws, "concepts/only.md"), "t", "b");
        assertThat(pages.dropWorkingFromLatest(ws, ProjectId.of("proj"))).isZero();
    }

    @Test
    void slotPagesAreSemanticAndSurviveTheSessionEndWorkingDrop() {
        // Regression guard (#26): a _slots/ page must be auto-pinned / sweep-exempt — it is classified
        // SEMANTIC (not WORKING), so the session-end working-drop never demotes it from latest.
        WorkspaceId ws = freshWorkspace();
        ProjectId proj = ProjectId.of("proj");
        PageRecord slot = pages.create(pageAt(ws, "_slots/identity.md"), "Identity", "who I am");
        PageRecord working = pages.create(pageAt(ws, "scratch/wip.md"), "t", "b");

        assertThat(slot.layer()).as("a slot is SEMANTIC, never WORKING").isEqualTo(MemoryLayer.SEMANTIC);

        int demoted = pages.dropWorkingFromLatest(ws, proj);
        assertThat(demoted).as("only the scratch page is demoted").isEqualTo(1);

        // The slot is still latest after the drop; the working page is gone from latest.
        assertThat(pages.readLatest(pageAt(ws, "_slots/identity.md")))
                .as("slot survives session end").isPresent();
        assertThat(pages.readLatest(pageAt(ws, "scratch/wip.md"))).isEmpty();
        assertThat(pages.listLatest(ws, proj)).extracting(PageRecord::id).contains(slot.id());
    }

    // --- the scorer bean is wired from config ---------------------------------------------------

    @Test
    void retentionScorerBeanUsesTheConfiguredDefaults() {
        // Confirms StoreConfiguration adapted the (default) decay config into the scorer.
        RetentionParameters p = scorer.parameters();
        assertThat(p.lambda()).isEqualTo(0.02);
        assertThat(p.sigma()).isEqualTo(1.0);
        assertThat(p.mu()).isEqualTo(0.01);
        assertThat(p.defaultSalience()).isEqualTo(1.0);
        assertThat(p.coldThreshold()).isEqualTo(0.05);

        // A freshly created page (age ~0, no access) scores ~ salience and is not cold.
        WorkspaceId ws = freshWorkspace();
        PageRecord fresh = pages.create(pageAt(ws, "concepts/fresh.md"), "t", "b");
        double score = fresh.retentionScore(scorer);
        assertThat(score).isGreaterThan(p.coldThreshold());
        assertThat(score).isLessThanOrEqualTo(p.defaultSalience() + 1e-9);
        Instant created = fresh.page().createdAt();
        assertThat(scorer.isCold(fresh.layer(), fresh.accessCount(), created, fresh.lastAccessedAt()))
                .isFalse();
    }
}
