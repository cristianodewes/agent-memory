package com.agentmemory.links;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.util.List;
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
 * End-to-end tests for scoped wikilinks (issue #27) against a throwaway {@code pgvector/pgvector:pg16}
 * Postgres (Testcontainers), proving the three acceptance criteria called out in the issue:
 *
 * <ul>
 *   <li><b>Deferred-safe resolution</b>: a link written before its target page exists is stored with
 *       {@code to_page_id = NULL} and self-heals — it re-points the moment the target is created,
 *       including across projects/workspaces.</li>
 *   <li><b>Automatic backlinks</b>: a resolved link shows up as a backlink on the target page, and a
 *       cross-project backlink carries the linking page's origin {@code (workspace, project)} and is
 *       flagged {@code crossProject}.</li>
 *   <li><b>Maintained on every write</b>: re-consolidating the source replaces its outgoing links
 *       (no duplicate accumulation across versions), and superseding the target re-points existing
 *       backlinks at the new latest version.</li>
 * </ul>
 *
 * <p>Pages are seeded through the real {@link PageRepository#create} writer path; links are then
 * maintained by {@link WikiLinkService#syncPageLinks} exactly as a caller (consolidation / #14
 * reindex) would. The offline {@code test} LLM provider is selected so the full context boots past
 * the DD-005 fail-fast gate; this test is about the link graph, not the LLM.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class WikiLinkServiceTest {

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
    @Autowired WikiLinkService links;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /** A fresh workspace per test keeps runs isolated on the shared container. */
    private WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    /** Seed a page through the real writer path and maintain its links, returning its record. */
    private PageRecord write(Identity id, String title, String body) {
        PageRecord record = pages.create(id, title, body);
        links.syncPageLinks(record);
        return record;
    }

    private static Identity page(WorkspaceId ws, String project, String path) {
        return Identity.ofPage(ws, ProjectId.of(project), PagePath.of(path));
    }

    private Long countLinksFrom(Identity source) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM links WHERE source_workspace = ? AND source_project = ? "
                        + "AND source_path = ?",
                Long.class,
                source.workspace().value(), source.project().value(), source.page().value());
    }

    // --- same-project resolution (baseline) --------------------------------------------------------

    @Test
    void linkToExistingPageResolvesImmediately() {
        WorkspaceId ws = freshWorkspace();
        Identity target = page(ws, "app", "concepts/recall.md");
        PageRecord targetRec = write(target, "Recall", "the recall page");

        Identity source = page(ws, "app", "concepts/index.md");
        write(source, "Index", "see [[concepts/recall]] for details");

        List<RelatedPage> outgoing = links.outgoingLinksOf(source);
        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.get(0).path()).isEqualTo("concepts/recall.md");
        assertThat(outgoing.get(0).title()).isEqualTo("Recall"); // resolved -> title present
        assertThat(outgoing.get(0).crossProject()).isFalse();

        // The link row points at the target's latest version.
        UUID toPageId = jdbc().queryForObject(
                "SELECT to_page_id FROM links WHERE source_path = ? AND source_workspace = ?",
                UUID.class, "concepts/index.md", ws.value());
        assertThat(toPageId).isEqualTo(targetRec.id().value());
    }

    // --- deferred-safe resolution (the headline #27 behaviour) -------------------------------------

    @Test
    void forwardLinkToMissingPageIsStoredDeferredThenSelfHealsWhenTargetAppears() {
        WorkspaceId ws = freshWorkspace();
        Identity source = page(ws, "app", "concepts/index.md");
        // Target concepts/recall.md does NOT exist yet.
        write(source, "Index", "see [[concepts/recall]] — written before the target exists");

        // Stored, but deferred: a row exists with NULL to_page_id / unresolved.
        assertThat(countLinksFrom(source)).isEqualTo(1L);
        Long unresolved = jdbc().queryForObject(
                "SELECT count(*) FROM links WHERE source_workspace = ? AND source_path = ? "
                        + "AND to_page_id IS NULL AND NOT target_resolved",
                Long.class, ws.value(), "concepts/index.md");
        assertThat(unresolved).isEqualTo(1L);
        // Outgoing view still shows the (deferred) link, with a null title.
        assertThat(links.outgoingLinksOf(source)).singleElement()
                .satisfies(r -> {
                    assertThat(r.path()).isEqualTo("concepts/recall.md");
                    assertThat(r.title()).isNull();
                });

        // Now the target page is created — the deferred link must re-point automatically.
        Identity target = page(ws, "app", "concepts/recall.md");
        PageRecord targetRec = write(target, "Recall", "the recall page, created later");

        UUID toPageId = jdbc().queryForObject(
                "SELECT to_page_id FROM links WHERE source_workspace = ? AND source_path = ?",
                UUID.class, ws.value(), "concepts/index.md");
        assertThat(toPageId).isEqualTo(targetRec.id().value());
        // And the backlink now shows on the target.
        assertThat(links.backlinksOf(target)).extracting(RelatedPage::path)
                .containsExactly("concepts/index.md");
    }

    @Test
    void deferredLinkSelfHealsAcrossProjects() {
        WorkspaceId ws = freshWorkspace();
        // Source in project "app" links to a page in sibling project "platform" that does not exist.
        Identity source = page(ws, "app", "decisions/use-platform.md");
        write(source, "Decision", "depends on [[platform:concepts/auth]] (sibling project)");

        assertThat(countLinksFrom(source)).isEqualTo(1L);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM links WHERE source_path = ? AND to_page_id IS NULL",
                Long.class, "decisions/use-platform.md")).isEqualTo(1L);

        // Create the cross-project target later.
        Identity target = page(ws, "platform", "concepts/auth.md");
        PageRecord targetRec = write(target, "Auth", "platform auth design");

        UUID toPageId = jdbc().queryForObject(
                "SELECT to_page_id FROM links WHERE source_path = ?",
                UUID.class, "decisions/use-platform.md");
        assertThat(toPageId).isEqualTo(targetRec.id().value());
    }

    // --- cross-project backlinks carry origin coordinates ------------------------------------------

    @Test
    void crossProjectBacklinkCarriesOriginWorkspaceAndProject() {
        WorkspaceId wsA = freshWorkspace();
        WorkspaceId wsB = freshWorkspace();

        // The target lives in wsA/app.
        Identity target = page(wsA, "app", "concepts/shared.md");
        write(target, "Shared concept", "the shared page");

        // A sibling-project page (same workspace, different project) links to it.
        Identity sibling = page(wsA, "platform", "notes/uses-shared.md");
        write(sibling, "Uses shared",
                "reuses [[app:concepts/shared]] from the app project");

        // A page in a *different workspace* links to it via the full workspace/project scope.
        Identity farAway = page(wsB, "infra", "runbooks/links-out.md");
        write(farAway, "Runbook",
                "see [[" + wsA.value() + "/app:concepts/shared]] across workspaces");

        List<RelatedPage> backlinks = links.backlinksOf(target);
        assertThat(backlinks).hasSize(2);
        // Both are cross-project and expose their true origin coordinates (not flattened to target's).
        assertThat(backlinks).allMatch(RelatedPage::crossProject);
        assertThat(backlinks).anySatisfy(r -> {
            assertThat(r.workspace()).isEqualTo(wsA.value());
            assertThat(r.project()).isEqualTo("platform");
            assertThat(r.path()).isEqualTo("notes/uses-shared.md");
            assertThat(r.title()).isEqualTo("Uses shared");
        });
        assertThat(backlinks).anySatisfy(r -> {
            assertThat(r.workspace()).isEqualTo(wsB.value());
            assertThat(r.project()).isEqualTo("infra");
            assertThat(r.path()).isEqualTo("runbooks/links-out.md");
        });
    }

    @Test
    void sameProjectBacklinkIsNotFlaggedCrossProject() {
        WorkspaceId ws = freshWorkspace();
        Identity target = page(ws, "app", "concepts/core.md");
        write(target, "Core", "core page");
        Identity source = page(ws, "app", "concepts/uses-core.md");
        write(source, "Uses core", "built on [[concepts/core]]");

        assertThat(links.backlinksOf(target)).singleElement()
                .satisfies(r -> {
                    assertThat(r.crossProject()).isFalse();
                    assertThat(r.project()).isEqualTo("app");
                    assertThat(r.path()).isEqualTo("concepts/uses-core.md");
                });
    }

    // --- maintained on every write -----------------------------------------------------------------

    @Test
    void reconsolidatingSourceReplacesOutgoingLinksNoDuplication() {
        WorkspaceId ws = freshWorkspace();
        write(page(ws, "app", "a.md"), "A", "a");
        write(page(ws, "app", "b.md"), "B", "b");

        Identity source = page(ws, "app", "index.md");
        write(source, "Index", "links to [[a]] and [[b]]");
        assertThat(countLinksFrom(source)).isEqualTo(2L);

        // Re-consolidate the source (new version) with a different link set.
        write(source, "Index", "now only links to [[b]]");
        assertThat(countLinksFrom(source)).isEqualTo(1L); // replaced, not accumulated
        assertThat(links.outgoingLinksOf(source)).extracting(RelatedPage::path)
                .containsExactly("b.md");
    }

    @Test
    void backlinkRepointsToLatestVersionAfterTargetSuperseded() {
        WorkspaceId ws = freshWorkspace();
        Identity target = page(ws, "app", "concepts/evolving.md");
        write(target, "Evolving", "v1 body");

        Identity source = page(ws, "app", "concepts/refers.md");
        write(source, "Refers", "refers to [[concepts/evolving]]");

        UUID firstTo = jdbc().queryForObject(
                "SELECT to_page_id FROM links WHERE source_path = ?", UUID.class, "concepts/refers.md");

        // Supersede the target with a new version; syncing it must re-point the existing backlink.
        PageRecord v2 = write(target, "Evolving", "v2 body");
        UUID secondTo = jdbc().queryForObject(
                "SELECT to_page_id FROM links WHERE source_path = ?", UUID.class, "concepts/refers.md");

        assertThat(secondTo).isEqualTo(v2.id().value());
        assertThat(secondTo).isNotEqualTo(firstTo);
        // The backlink view still shows exactly the one linking page (resolved against latest).
        assertThat(links.backlinksOf(target)).extracting(RelatedPage::path)
                .containsExactly("concepts/refers.md");
    }

    // --- read-side guards --------------------------------------------------------------------------

    @Test
    void backlinksRequiresPageScopedIdentity() {
        assertThatThrownBy(() -> links.backlinksOf(
                Identity.ofProject(freshWorkspace(), ProjectId.of("app"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
