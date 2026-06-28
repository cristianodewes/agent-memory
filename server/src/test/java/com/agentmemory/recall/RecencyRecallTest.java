package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.MemoryLayer;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end proof of the recall recency prior (issue #140) over a throwaway {@code pgvector/pgvector:pg16}
 * (Testcontainers): between two equal-relevance pages the more recently updated one ranks higher, and a
 * semantic (timeless) page does <em>not</em> decay.
 *
 * <p>The prior lives in the {@code recall} fusion, so the tests drive the <em>base</em> hybrid service
 * (the {@code recallService} bean) directly — bypassing the LLM-recall decorator — to assert the
 * decay-in-fusion ordering deterministically, with no cross-encoder/LLM re-rank in the way.
 *
 * <p>Each test seeds two pages with <strong>identical</strong> title+body (so their {@code ts_rank} is
 * equal) but assigns the page that the prior must <em>demote</em> the greater page id, so the FTS arm
 * ranks it first by {@code p.id DESC}: without the prior the demoted page would win, so an assertion that
 * the other page ranks first proves the prior — not a tie-break — drove the order. The decay reads the
 * shared system clock against the seeded {@code updated_at}, so ages use a large day gap to stay robust
 * to the sub-second skew between the app clock and the DB {@code now()}.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class RecencyRecallTest {

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

    /** The base hybrid recall (where the recency prior is applied), not the LLM-assisted decorator. */
    @Autowired @Qualifier("recallService") RecallService recall;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * A pair of fresh, unique page ids ordered {@code [greater, lesser]} by Postgres uuid byte order
     * (equivalently, lexicographic order of the canonical lowercase hex). Assigning the page the prior
     * must <em>demote</em> the greater id makes the FTS arm rank it first under
     * {@code ORDER BY rank DESC, p.id DESC} when {@code ts_rank} ties — so an assertion that the other
     * page wins proves the prior, not a tie-break, drove the order. Random per call so two tests sharing
     * the container never collide on {@code pages_pkey}.
     */
    private static UUID[] orderedPairDesc() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String ha = a.toString().replace("-", "");
        String hb = b.toString().replace("-", "");
        return ha.compareTo(hb) >= 0 ? new UUID[] {a, b} : new UUID[] {b, a};
    }

    private Scope freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        JdbcTemplate j = jdbc();
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        j.update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        j.update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return Scope.of(ws, proj);
    }

    private UUID workspaceId(Scope s) {
        return jdbc().queryForObject(
                "SELECT id FROM workspaces WHERE slug = ?", UUID.class, s.workspaceSlug());
    }

    private UUID projectId(Scope s) {
        return jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?",
                UUID.class, s.workspaceSlug(), s.projectSlug());
    }

    /** Insert a latest page aged {@code ageDays} days (both updated_at and created_at) in a layer. */
    private void seedAgedPage(
            Scope s, UUID id, String path, String title, String body, int ageDays, MemoryLayer layer) {
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, body, "
                        + "is_latest, access_count, layer, updated_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0, ?, "
                        + "        now() - make_interval(days => ?), now() - make_interval(days => ?))",
                id, workspaceId(s), projectId(s), s.workspaceSlug(), s.projectSlug(), path, title, body,
                layer.wire(), ageDays, ageDays);
    }

    @Test
    void betweenEqualRelevanceHitsTheMoreRecentRanksHigher() {
        Scope s = freshScope();
        UUID[] ids = orderedPairDesc();
        UUID staleId = ids[0]; // greater id → FTS rank 1; without the prior the stale page would win
        UUID freshId = ids[1];
        String title = "Recency probe";
        String body = "the recency fusion probe keyword shared by both pages";
        seedAgedPage(s, staleId, "notes/stale.md", title, body, 120, MemoryLayer.EPISODIC);
        seedAgedPage(s, freshId, "notes/fresh.md", title, body, 0, MemoryLayer.EPISODIC);

        RecallResult r = recall.search(RecallQuery.of("recency fusion probe keyword", s));

        assertThat(r.rawFallback()).isFalse();
        assertThat(r.hits()).hasSize(2);
        RecallHit top = r.hits().get(0);
        // The fresher page (lower base RRF rank) is lifted above the stale one by the recency prior.
        assertThat(top.path()).isEqualTo("notes/fresh.md");
        assertThat(top.id()).isEqualTo(freshId.toString());
        assertThat(top.score()).isGreaterThan(r.hits().get(1).score());
        // The metadata the render layer needs rode through on the hit.
        assertThat(top.layer()).isEqualTo(MemoryLayer.EPISODIC);
        assertThat(top.updatedAt()).isNotNull();
    }

    @Test
    void semanticPageDoesNotDecay() {
        Scope s = freshScope();
        UUID[] ids = orderedPairDesc();
        UUID episodicId = ids[0]; // greater id → base rank 1
        UUID semanticId = ids[1]; // lower id → base rank 2
        String title = "Timeless probe";
        String body = "the timeless decay probe keyword shared by both pages";
        // Both equally old (120d). Episodic decays, semantic does not, so semantic must win despite its
        // lower base RRF rank.
        seedAgedPage(s, episodicId, "notes/episodic.md", title, body, 120, MemoryLayer.EPISODIC);
        seedAgedPage(s, semanticId, "notes/semantic.md", title, body, 120, MemoryLayer.SEMANTIC);

        RecallResult r = recall.search(RecallQuery.of("timeless decay probe keyword", s));

        assertThat(r.hits()).hasSize(2);
        RecallHit top = r.hits().get(0);
        assertThat(top.path()).isEqualTo("notes/semantic.md");
        assertThat(top.layer()).isEqualTo(MemoryLayer.SEMANTIC);
        // The undecayed semantic page far outscores the decayed episodic one of the same age.
        assertThat(top.score()).isGreaterThan(r.hits().get(1).score() * 10);
    }

    @Test
    void recencyMetadataIsPopulatedOnHits() {
        Scope s = freshScope();
        seedAgedPage(s, UUID.randomUUID(), "notes/a.md", "Meta probe", "metadata probe keyword body", 3,
                MemoryLayer.PROCEDURAL);

        RecallResult r = recall.search(RecallQuery.of("metadata probe keyword", s));

        assertThat(r.hits()).hasSize(1);
        RecallHit hit = r.hits().get(0);
        // updated_at + layer are read from the page row by the candidate mapper (issue #140).
        assertThat(hit.updatedAt()).isNotNull();
        assertThat(hit.layer()).isEqualTo(MemoryLayer.PROCEDURAL);
    }
}
