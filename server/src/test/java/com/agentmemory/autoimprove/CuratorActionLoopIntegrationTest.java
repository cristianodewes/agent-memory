package com.agentmemory.autoimprove;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.curate.CuratorService;
import com.agentmemory.eval.EvalGate;
import com.agentmemory.eval.EvalGateProbe;
import com.agentmemory.eval.EvalGateProperties;
import com.agentmemory.forget.ForgetSweepService;
import com.agentmemory.links.WikiLinkParser;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
 * Proves the curator corrective-action loop (issue #101) is wired <strong>end-to-end and ON</strong>
 * against a real {@code pgvector/pgvector:pg16} + real wiki/git: a #29 curator finding becomes an
 * action-shaped proposal, lands in {@code pending_writes}, is eval-gated (#31), and — applied directly or
 * after approval — runs the <em>real</em> corrective action against Postgres.
 *
 * <ul>
 *   <li><b>auto-apply</b> — with the loop enabled and the eval gate off, a {@code COLD_EPISODIC} page is
 *       really soft-deleted ({@code page.forget}) and a {@code DANGLING_CROSS_PROJECT} link is really
 *       pruned from its source page's body ({@code link.fix}); a second pass is quiescent.</li>
 *   <li><b>eval fail-closed</b> — with the eval gate ON and blocking, the {@code page.forget} proposal is
 *       {@code rejected} with a BLOCKED verdict and the page is <em>not</em> forgotten.</li>
 *   <li><b>approval</b> — with {@code require_approval}, the action is held {@code proposed} and only runs
 *       once a human approves it.</li>
 * </ul>
 *
 * <p>The loop is assembled here from the autowired real services (curator, write service, forget sweep,
 * wikilink parser) exactly as {@link AutoImproveConfiguration} wires it, so the production seams are the
 * ones under test. The eval gate uses the portable {@link EvalGateProbe} subprocess (Windows/Linux safe).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class CuratorActionLoopIntegrationTest {

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

    @Autowired CuratorService curator;
    @Autowired PageRepository pages;
    @Autowired MemoryWriteService writes;
    @Autowired ForgetSweepService forget;
    @Autowired WikiLinkParser parser;
    @Autowired WikiLinkService links;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- the loop, assembled exactly as AutoImproveConfiguration wires it ---------------------------

    /** The pieces a test drives: the scope-level scheduler, the gate (for approve), the proposal store. */
    private record Loop(CuratorActionScheduler scheduler, AutoImproveGate gate, JdbcPendingWriteRepository pending) {}

    private Loop loop(EvalGate eval, boolean requireApproval) {
        JdbcPendingWriteRepository pending = new JdbcPendingWriteRepository(jdbc());
        CuratorActionRepository scopeRepo = new CuratorActionRepository(jdbc());
        CuratorActionProposalSource source = new CuratorActionProposalSource(curator);
        DispatchingProposalApplier applier = new DispatchingProposalApplier(
                new ContentProposalApplier(writes),
                new ForgetProposalApplier(forget),
                new LinkFixProposalApplier(pages, parser, writes));
        AutoImproveProperties props = new AutoImproveProperties(
                requireApproval, 3, 20,
                new AutoImproveProperties.Scheduler(false, null),
                new AutoImproveProperties.CuratorActions(true, null, 50)); // curator-actions ON
        AutoImproveGate gate = new AutoImproveGate(pending, applier, props, eval);
        CuratorActionScheduler scheduler = new CuratorActionScheduler(scopeRepo, pending, gate, source, props);
        return new Loop(scheduler, gate, pending);
    }

    /** The off (disabled) eval gate — every proposal is SKIPPED and applies as before. */
    private static EvalGate disabledEval() {
        return new EvalGate(new EvalGateProperties(false, null, null, null, 0, null));
    }

    /** An eval gate that runs the probe in {@code mode} over the given path prefixes. */
    private static EvalGate probeEval(String mode, List<String> prefixes) {
        return new EvalGate(new EvalGateProperties(
                true, prefixes, probe(mode), Duration.ofSeconds(30), 65_536, null));
    }

    // --- seeding -----------------------------------------------------------------------------------

    private static Scope freshScope() {
        return Scope.of("ws" + UUID.randomUUID().toString().replace("-", ""), "proj");
    }

    /** A fresh page whose body links to a non-existent page in a sibling project (a dangling cross-project
     * link once aged), written + link-synced through the real path; returns its identity. */
    private Identity seedDanglingLinkPage(Scope scope, String path) {
        Identity src = Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(path));
        PageRecord rec = pages.create(src, "Use platform", "see [[platform:concepts/auth]] for details");
        links.syncPageLinks(rec);
        // Age the unresolved link past the 7d dangling cutoff so the curator's dangling rule fires.
        jdbc().update(
                "UPDATE links SET created_at = now() - interval '30 days' "
                        + "WHERE source_workspace = ? AND source_path = ? AND NOT target_resolved",
                scope.workspaceSlug(), PagePath.of(path).value());
        return src;
    }

    /** Raw-insert a cold episodic page (40d old, never accessed) so the curator's COLD_EPISODIC rule fires.
     * Reuses the workspace/project rows {@link #seedDanglingLinkPage}'s {@code pages.create} materialized. */
    private void seedColdEpisodic(Scope scope, String path) {
        UUID wsId = jdbc().queryForObject(
                "SELECT id FROM workspaces WHERE slug = ?", UUID.class, scope.workspaceSlug());
        UUID projId = jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?",
                UUID.class, scope.workspaceSlug(), scope.projectSlug());
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, body, "
                        + "is_latest, access_count, layer, created_at, updated_at, last_accessed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'stale notes', true, 0, 'episodic', ?, ?, NULL)",
                UUID.randomUUID(), wsId, projId, scope.workspaceSlug(), scope.projectSlug(), path,
                "Old session", java.sql.Timestamp.from(old), java.sql.Timestamp.from(old));
    }

    /** Ensure a bare project exists (for the eval test, which seeds only a raw cold page). */
    private void ensureProject(Scope scope) {
        UUID wsId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING",
                wsId, scope.workspaceSlug());
        UUID realWsId = jdbc().queryForObject(
                "SELECT id FROM workspaces WHERE slug = ?", UUID.class, scope.workspaceSlug());
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), realWsId, scope.workspaceSlug(), scope.projectSlug());
    }

    private int liveLatestCount(Scope scope, String path) {
        Integer n = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND path = ? "
                        + "AND is_latest AND deleted_at IS NULL",
                Integer.class, scope.workspaceSlug(), scope.projectSlug(), PagePath.of(path).value());
        return n == null ? 0 : n;
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    @Timeout(120)
    void loopOnAutoAppliesForgetAndLinkFixAgainstRealPostgres() {
        Scope scope = freshScope();
        Identity src = seedDanglingLinkPage(scope, "decisions/use.md"); // -> DANGLING_CROSS_PROJECT
        seedColdEpisodic(scope, "sessions/old.md");                     // -> COLD_EPISODIC

        Loop loop = loop(disabledEval(), /*requireApproval*/ false);
        int submitted = loop.scheduler().runScope(scope);

        // Each actionable finding became its own proposal (per-finding granularity).
        assertThat(submitted).isEqualTo(2);
        List<PendingWriteRecord> rows = loop.pending().recent(scope, 10);
        assertThat(rows).extracting(PendingWriteRecord::kind)
                .containsExactlyInAnyOrder(ProposalKinds.PAGE_FORGET, ProposalKinds.LINK_FIX);
        assertThat(rows).allSatisfy(r -> assertThat(r.status()).isEqualTo(PendingWriteStatus.APPLIED));

        // page.forget really soft-deleted the cold page (gone from latest).
        assertThat(liveLatestCount(scope, "sessions/old.md")).isZero();

        // link.fix really pruned the dangling link from the source body, and the graph edge is gone.
        PageRecord after = pages.readLatest(src).orElseThrow();
        assertThat(after.page().body()).doesNotContain("platform:concepts/auth");
        Integer danglingLinks = jdbc().queryForObject(
                "SELECT count(*) FROM links WHERE source_workspace = ? AND source_path = ? "
                        + "AND NOT target_resolved",
                Integer.class, scope.workspaceSlug(), "decisions/use.md");
        assertThat(danglingLinks).isZero();

        // Quiescent: the findings are gone, so a second audit proposes nothing.
        assertThat(loop.scheduler().runScope(scope)).isZero();
    }

    @Test
    @Timeout(120)
    void evalGateBlocksTheForgetFailClosedAndThePageSurvives() {
        Scope scope = freshScope();
        ensureProject(scope);
        seedColdEpisodic(scope, "sessions/old.md");

        // Eval gate ON + blocking, scoped to the cold page's prefix so the forget is actually evaluated.
        Loop loop = loop(probeEval("fail", List.of("sessions/")), /*requireApproval*/ false);
        loop.scheduler().runScope(scope);

        PendingWriteRecord forgetRow = loop.pending().recent(scope, 10).stream()
                .filter(r -> r.kind().equals(ProposalKinds.PAGE_FORGET)).findFirst().orElseThrow();
        assertThat(forgetRow.status()).isEqualTo(PendingWriteStatus.REJECTED);
        assertThat(forgetRow.evalResult()).contains("BLOCKED");
        // Fail-closed: the page was NOT forgotten.
        assertThat(liveLatestCount(scope, "sessions/old.md")).isEqualTo(1);
    }

    @Test
    @Timeout(120)
    void requireApprovalHoldsTheActionUntilApproved() {
        Scope scope = freshScope();
        ensureProject(scope);
        seedColdEpisodic(scope, "sessions/old.md");

        Loop loop = loop(disabledEval(), /*requireApproval*/ true);
        loop.scheduler().runScope(scope);

        PendingWriteRecord held = loop.pending().recent(scope, 10).stream()
                .filter(r -> r.kind().equals(ProposalKinds.PAGE_FORGET)).findFirst().orElseThrow();
        assertThat(held.status()).isEqualTo(PendingWriteStatus.PROPOSED);
        assertThat(liveLatestCount(scope, "sessions/old.md")).isEqualTo(1); // not yet forgotten

        PendingWriteRecord applied = loop.gate().approve(held.id());

        assertThat(applied.status()).isEqualTo(PendingWriteStatus.APPLIED);
        assertThat(liveLatestCount(scope, "sessions/old.md")).isZero(); // forgotten only on approval
    }

    // --- portable eval probe (same fixture EvalGateTest / AutoImproveEvalWiringIntegrationTest use) ---

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
}
