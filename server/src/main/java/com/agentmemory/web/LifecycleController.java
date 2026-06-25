package com.agentmemory.web;

import com.agentmemory.core.Identity;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.lifecycle.LifecycleException;
import com.agentmemory.lifecycle.ProjectLifecycleService;
import com.agentmemory.lifecycle.ProjectOpResult;
import com.agentmemory.lifecycle.ResetResult;
import com.agentmemory.lifecycle.ResetService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project lifecycle + reset endpoints (issue #33). The thin Go CLI drives these; the client never
 * touches Postgres or the wiki directly (§2.1).
 *
 * <ul>
 *   <li>{@code POST /projects/rename} — rename a project within its workspace.</li>
 *   <li>{@code POST /projects/move} — move a project to another workspace (optionally rename).</li>
 *   <li>{@code POST /projects/purge} — delete a project's wiki subtree + DB rows (idempotent).</li>
 *   <li>{@code POST /reset} — wipe everything, guarded by the live-process check (invariant #9).</li>
 * </ul>
 *
 * <p>Both services are injected via {@link ObjectProvider} so a DB-less context still constructs this
 * controller but answers {@code 503}; a {@link LifecycleException} (destination exists, source missing)
 * maps to {@code 409 Conflict}, a bad request body to {@code 400}.
 */
@RestController
public class LifecycleController {

    private static final Logger log = LoggerFactory.getLogger(LifecycleController.class);

    private final ObjectProvider<ProjectLifecycleService> projectOps;
    private final ObjectProvider<ResetService> resetOps;

    public LifecycleController(
            ObjectProvider<ProjectLifecycleService> projectOps, ObjectProvider<ResetService> resetOps) {
        this.projectOps = projectOps;
        this.resetOps = resetOps;
    }

    @PostMapping("/projects/rename")
    public ResponseEntity<Map<String, Object>> rename(@RequestBody RenameRequest req) {
        ProjectLifecycleService svc = projectOps.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            Identity from = projectIdentity(req.workspace(), req.project());
            ProjectOpResult result = svc.renameProject(from, ProjectId.of(req.newProject()));
            return ResponseEntity.ok(opBody(result));
        } catch (LifecycleException e) {
            return conflict(e);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PostMapping("/projects/move")
    public ResponseEntity<Map<String, Object>> move(@RequestBody MoveRequest req) {
        ProjectLifecycleService svc = projectOps.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            Identity from = projectIdentity(req.workspace(), req.project());
            // newProject is optional: keep the name when only moving workspaces.
            String newProj = (req.newProject() == null || req.newProject().isBlank())
                    ? req.project() : req.newProject();
            ProjectOpResult result = svc.moveProject(
                    from, WorkspaceId.of(req.newWorkspace()), ProjectId.of(newProj));
            return ResponseEntity.ok(opBody(result));
        } catch (LifecycleException e) {
            return conflict(e);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PostMapping("/projects/purge")
    public ResponseEntity<Map<String, Object>> purge(@RequestBody PurgeRequest req) {
        ProjectLifecycleService svc = projectOps.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            Identity project = projectIdentity(req.workspace(), req.project());
            ProjectOpResult result = svc.purgeProject(project);
            return ResponseEntity.ok(opBody(result));
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody(required = false) ResetRequest req) {
        ResetService svc = resetOps.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        boolean force = req != null && req.force();
        ResetResult result = svc.reset(force);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("performed", result.performed());
        body.put("reason", result.reason());
        body.put("liveHolderPid", result.liveHolderPid());
        body.put("tablesCleared", result.tablesCleared());
        // A refusal due to a live process is a 409 Conflict (the precondition — no live holder — failed).
        HttpStatus status = result.performed() ? HttpStatus.OK : HttpStatus.CONFLICT;
        if (!result.performed()) {
            log.warn("reset refused: {}", result.reason());
        }
        return ResponseEntity.status(status).body(body);
    }

    // --- helpers --------------------------------------------------------------------------------

    private static Identity projectIdentity(String workspace, String project) {
        if (workspace == null || project == null) {
            throw new IllegalArgumentException("workspace and project are required");
        }
        return Identity.ofProject(WorkspaceId.of(workspace), ProjectId.of(project));
    }

    private static Map<String, Object> opBody(ProjectOpResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", r.op());
        body.put("before", identityMap(r.before()));
        body.put("after", identityMap(r.after()));
        body.put("pagesAffected", r.pagesAffected());
        body.put("linksRepointed", r.linksRepointed());
        return body;
    }

    private static Map<String, Object> identityMap(Identity id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspace", id.workspace().value());
        m.put("project", id.project().value());
        return m;
    }

    private static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable",
                        "reason", "lifecycle ops not configured (no datasource/wiki)"));
    }

    private static ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", "conflict", "reason", String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", "error", "reason", String.valueOf(e.getMessage())));
    }

    // --- request bodies -------------------------------------------------------------------------

    /** {@code POST /projects/rename}: rename {@code (workspace, project)} → {@code (workspace, newProject)}. */
    public record RenameRequest(String workspace, String project, String newProject) {
    }

    /**
     * {@code POST /projects/move}: move {@code (workspace, project)} → {@code (newWorkspace, newProject)};
     * {@code newProject} optional (keeps {@code project} when blank).
     */
    public record MoveRequest(String workspace, String project, String newWorkspace, String newProject) {
    }

    /** {@code POST /projects/purge}: purge {@code (workspace, project)}. */
    public record PurgeRequest(String workspace, String project) {
    }

    /** {@code POST /reset}: wipe everything; {@code force} bypasses the live-process refusal. */
    public record ResetRequest(boolean force) {
    }
}
