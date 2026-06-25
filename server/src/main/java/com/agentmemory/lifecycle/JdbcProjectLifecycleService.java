package com.agentmemory.lifecycle;

import com.agentmemory.core.Identity;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.AuditWriter;
import com.agentmemory.store.LinkRepository;
import com.agentmemory.wiki.WikiPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcTemplate}-backed {@link ProjectLifecycleService} (issue #33). Each op runs in one
 * transaction that rewrites/deletes the Postgres rows, then performs the wiki subtree move/delete; the
 * filesystem step is sequenced <em>inside</em> the transaction so a failure throws and rolls the DB
 * back, keeping the index and the wiki source of truth consistent (the same couple-fs-to-tx discipline
 * #12/#13 use). Cross-project {@code links} are re-pointed via {@link LinkRepository}, and an
 * {@code audit_log} row with before/after identity is written via {@link AuditWriter}.
 *
 * <p>Identity is the typed 3-tuple, so a rename is a slug update across the denormalized columns and a
 * purge is row deletes + an {@code rm -rf} of one subtree — sibling projects, which live under
 * different slugs/dirs, are never touched (design goal #6).
 */
public class JdbcProjectLifecycleService implements ProjectLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(JdbcProjectLifecycleService.class);

    /** Denormalized tables carrying a {@code project} slug column to rewrite on rename. */
    private static final List<String> PROJECT_SLUG_TABLES =
            List.of("pages", "sessions", "observations", "audit_log");

    private final JdbcTemplate jdbc;
    private final LinkRepository links;
    private final AuditWriter audit;
    private final WikiPaths wikiPaths;
    private final WikiDirOps wikiDirOps;

    public JdbcProjectLifecycleService(
            JdbcTemplate jdbc, LinkRepository links, AuditWriter audit,
            WikiPaths wikiPaths, WikiDirOps wikiDirOps) {
        this.jdbc = jdbc;
        this.links = links;
        this.audit = audit;
        this.wikiPaths = wikiPaths;
        this.wikiDirOps = wikiDirOps;
    }

    // --- rename ---------------------------------------------------------------------------------

    @Override
    @Transactional
    public ProjectOpResult renameProject(Identity project, ProjectId newName) {
        requireProjectScoped(project);
        if (newName == null) {
            throw new IllegalArgumentException("newName must not be null");
        }
        WorkspaceId ws = project.workspace();
        ProjectId oldName = project.project();
        if (oldName.value().equals(newName.value())) {
            // No-op rename: nothing to do, report zero changes (idempotent).
            return new ProjectOpResult("rename", project, project, 0, 0);
        }
        return relocate("rename", project, ws, newName);
    }

    // --- move -----------------------------------------------------------------------------------

    @Override
    @Transactional
    public ProjectOpResult moveProject(Identity project, WorkspaceId newWorkspace, ProjectId newName) {
        requireProjectScoped(project);
        if (newWorkspace == null || newName == null) {
            throw new IllegalArgumentException("newWorkspace and newName must not be null");
        }
        if (project.workspace().value().equals(newWorkspace.value())
                && project.project().value().equals(newName.value())) {
            return new ProjectOpResult("move", project, project, 0, 0);
        }
        return relocate("move", project, newWorkspace, newName);
    }

    /**
     * Shared rename/move body: validates existence + no destination collision, ensures the target
     * workspace/project rows, rewrites every denormalized slug + FK, re-points links, moves the wiki
     * subtree, and audits. Runs within the caller's transaction.
     */
    private ProjectOpResult relocate(
            String op, Identity from, WorkspaceId newWs, ProjectId newProj) {
        WorkspaceId oldWs = from.workspace();
        ProjectId oldProj = from.project();
        Identity to = Identity.ofProject(newWs, newProj);

        if (!projectExists(oldWs, oldProj)) {
            throw new LifecycleException(
                    "project " + identityLabel(oldWs, oldProj) + " does not exist; cannot " + op);
        }
        if (projectExists(newWs, newProj)) {
            throw new LifecycleException(
                    "destination project " + identityLabel(newWs, newProj)
                            + " already exists; refusing to " + op + " onto an existing project");
        }

        java.util.UUID newWsId = getOrCreateWorkspace(newWs.value());
        java.util.UUID newProjId = getOrCreateProject(newWsId, newWs.value(), newProj.value());

        // Re-point every denormalized (workspace, project) slug + FK id to the destination. Order does
        // not matter within the transaction; the partial-unique page index is keyed on (ws, proj, path)
        // so moving onto a NON-existing destination (checked above) cannot collide.
        int pages = repointTable("pages", oldWs, oldProj, newWs, newProj, newWsId, newProjId);
        repointTable("sessions", oldWs, oldProj, newWs, newProj, newWsId, newProjId);
        repointTable("observations", oldWs, oldProj, newWs, newProj, newWsId, newProjId);
        repointAuditLog(oldWs, oldProj, newWs, newProj);
        int linksRepointed = links.repointProject(oldWs, oldProj, newWs, newProj);

        // Retire the now-empty source project row (its children have moved to the new project id).
        jdbc.update("DELETE FROM projects WHERE workspace = ? AND slug = ?",
                oldWs.value(), oldProj.value());

        // Move the wiki subtree (git-committed). Inside the tx: a filesystem failure rolls the DB back.
        wikiDirOps.moveProjectDir(
                wikiPaths.projectDir(toPageScopedProbe(from)),
                wikiPaths.projectDir(toPageScopedProbe(to)),
                op + " project " + identityLabel(oldWs, oldProj) + " -> " + identityLabel(newWs, newProj));

        audit.record(to, "project." + op, "project", identityChangeJson(from, to));
        log.info("{} project {} -> {}: pages={}, links={}",
                op, identityLabel(oldWs, oldProj), identityLabel(newWs, newProj), pages, linksRepointed);
        return new ProjectOpResult(op, from, to, pages, linksRepointed);
    }

    // --- purge ----------------------------------------------------------------------------------

    @Override
    @Transactional
    public ProjectOpResult purgeProject(Identity project) {
        requireProjectScoped(project);
        WorkspaceId ws = project.workspace();
        ProjectId proj = project.project();

        if (!projectExists(ws, proj)) {
            // Idempotent: still remove any stray wiki subtree, then report no-op.
            wikiDirOps.deleteProjectDir(
                    wikiPaths.projectDir(toPageScopedProbe(project)),
                    "purge project " + identityLabel(ws, proj) + " (no db rows)");
            return new ProjectOpResult("purge", project, project, 0, 0);
        }

        // Count pages for the report before they cascade away.
        Integer pageCount = jdbc.queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ?",
                Integer.class, ws.value(), proj.value());

        // Delete capture + audit rows for the project (no project FK on these), then the project row
        // (pages → cascade page_embeddings + links-from; sessions → cascade observations).
        jdbc.update("DELETE FROM observations WHERE workspace = ? AND project = ?",
                ws.value(), proj.value());
        jdbc.update("DELETE FROM sessions WHERE workspace = ? AND project = ?",
                ws.value(), proj.value());
        jdbc.update("DELETE FROM audit_log WHERE workspace = ? AND project = ?",
                ws.value(), proj.value());
        // Cross-project links TARGETING this project would otherwise be left dangling (their to_page_id
        // is SET NULL by the page cascade, but the slug columns would persist); delete them explicitly.
        jdbc.update("DELETE FROM links WHERE target_workspace = ? AND target_project = ?",
                ws.value(), proj.value());
        // Deleting the project row cascades pages (→ embeddings, links-from), via ON DELETE CASCADE.
        jdbc.update("DELETE FROM projects WHERE workspace = ? AND slug = ?",
                ws.value(), proj.value());

        wikiDirOps.deleteProjectDir(
                wikiPaths.projectDir(toPageScopedProbe(project)),
                "purge project " + identityLabel(ws, proj));

        audit.record(project, "project.purge", "project", identityChangeJson(project, project));
        int pages = pageCount == null ? 0 : pageCount;
        log.info("purged project {}: pages={}", identityLabel(ws, proj), pages);
        return new ProjectOpResult("purge", project, project, pages, 0);
    }

    // --- DB helpers -----------------------------------------------------------------------------

    private boolean projectExists(WorkspaceId ws, ProjectId proj) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM projects WHERE workspace = ? AND slug = ?",
                Integer.class, ws.value(), proj.value());
        return n != null && n > 0;
    }

    /** Rewrite one table's denormalized (workspace, project) slugs + FK ids for rows in the project. */
    private int repointTable(
            String table, WorkspaceId oldWs, ProjectId oldProj, WorkspaceId newWs, ProjectId newProj,
            java.util.UUID newWsId, java.util.UUID newProjId) {
        return jdbc.update(
                "UPDATE " + table + " SET workspace = ?, project = ?, "
                        + "workspace_id = ?, project_id = ? "
                        + "WHERE workspace = ? AND project = ?",
                newWs.value(), newProj.value(), newWsId, newProjId,
                oldWs.value(), oldProj.value());
    }

    /** audit_log has no FK id columns, only denormalized slugs. */
    private int repointAuditLog(WorkspaceId oldWs, ProjectId oldProj, WorkspaceId newWs, ProjectId newProj) {
        return jdbc.update(
                "UPDATE audit_log SET workspace = ?, project = ? WHERE workspace = ? AND project = ?",
                newWs.value(), newProj.value(), oldWs.value(), oldProj.value());
    }

    /** Get-or-create the workspace by slug, returning its surrogate id (mirrors store get-or-create). */
    private java.util.UUID getOrCreateWorkspace(String slug) {
        jdbc.update("INSERT INTO workspaces (id, slug) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING",
                com.agentmemory.core.Uuid7.randomUuid(), slug);
        return jdbc.queryForObject("SELECT id FROM workspaces WHERE slug = ?", java.util.UUID.class, slug);
    }

    private java.util.UUID getOrCreateProject(java.util.UUID workspaceId, String workspace, String slug) {
        jdbc.update(
                "INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (workspace, slug) DO NOTHING",
                com.agentmemory.core.Uuid7.randomUuid(), workspaceId, workspace, slug);
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?",
                java.util.UUID.class, workspace, slug);
    }

    // --- misc -----------------------------------------------------------------------------------

    private static void requireProjectScoped(Identity project) {
        if (project == null) {
            throw new IllegalArgumentException("project identity must not be null");
        }
        if (project.isPageScoped()) {
            throw new IllegalArgumentException(
                    "lifecycle ops take a project-scoped identity (no path): " + project);
        }
    }

    /**
     * {@link WikiPaths#projectDir} requires a page-scoped identity (it guards path-escape); a project
     * op has no page, so wrap the project coordinates with a fixed sentinel page purely to reuse the
     * {@code wiki/<ws>/<project>} resolution. Only the parent project dir is used by the caller.
     */
    private static Identity toPageScopedProbe(Identity projectScoped) {
        return Identity.ofPage(
                projectScoped.workspace(), projectScoped.project(),
                com.agentmemory.core.PagePath.of("_.md"));
    }

    private static String identityLabel(WorkspaceId ws, ProjectId proj) {
        return ws.value() + "/" + proj.value();
    }

    private static String identityChangeJson(Identity from, Identity to) {
        return "{\"from\":{\"workspace\":" + jsonString(from.workspace().value())
                + ",\"project\":" + jsonString(from.project().value()) + "},"
                + "\"to\":{\"workspace\":" + jsonString(to.workspace().value())
                + ",\"project\":" + jsonString(to.project().value()) + "}}";
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /**
     * Filesystem subtree move/delete for the wiki, committed to git. Separated behind a tiny interface
     * so the lifecycle service stays focused on the DB/identity logic and the fs/git concern is wired
     * (and unit-substitutable) on its own.
     */
    public interface WikiDirOps {

        /**
         * Move {@code wiki/<ws>/<old>} → {@code wiki/<ws2>/<new>} and commit. No-op (no commit) if the
         * source dir does not exist (a project with no materialized pages yet).
         *
         * @param fromDir       the source project dir (absolute, under the wiki root).
         * @param toDir         the destination project dir (absolute, under the wiki root).
         * @param commitMessage the git commit message.
         * @throws LifecycleException if the destination dir already exists or the move fails.
         */
        void moveProjectDir(Path fromDir, Path toDir, String commitMessage);

        /**
         * Recursively delete {@code wiki/<ws>/<project>} and commit. No-op if it does not exist
         * (idempotent purge).
         *
         * @param dir           the project dir to delete (absolute, under the wiki root).
         * @param commitMessage the git commit message.
         */
        void deleteProjectDir(Path dir, String commitMessage);
    }

    /**
     * Default {@link WikiDirOps}: real filesystem move/recursive-delete sequenced with a
     * {@code com.agentmemory.wiki.WikiGit} commit. Kept as a nested static so the production wiring is
     * one class while tests can supply a no-op/fake.
     */
    public static final class GitWikiDirOps implements WikiDirOps {

        private final com.agentmemory.wiki.WikiGit git;

        public GitWikiDirOps(com.agentmemory.wiki.WikiGit git) {
            this.git = git;
        }

        @Override
        public void moveProjectDir(Path fromDir, Path toDir, String commitMessage) {
            if (!Files.isDirectory(fromDir)) {
                return; // nothing materialized to move
            }
            if (Files.exists(toDir)) {
                throw new LifecycleException(
                        "destination wiki dir already exists: " + toDir + "; refusing to overwrite");
            }
            try {
                Files.createDirectories(toDir.getParent());
                Files.move(fromDir, toDir);
            } catch (IOException e) {
                throw new UncheckedIOException("failed to move wiki dir " + fromDir + " -> " + toDir, e);
            }
            git.stageAndCommit(commitMessage, List.of(fromDir, toDir));
        }

        @Override
        public void deleteProjectDir(Path dir, String commitMessage) {
            if (!Files.exists(dir)) {
                return; // idempotent
            }
            deleteRecursively(dir);
            git.stageAndCommit(commitMessage, List.of(dir));
        }

        private static void deleteRecursively(Path dir) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException("failed to delete " + p, e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("failed to walk wiki dir for delete " + dir, e);
            }
        }
    }
}
