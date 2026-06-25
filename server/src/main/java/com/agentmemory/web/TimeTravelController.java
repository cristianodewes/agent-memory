package com.agentmemory.web;

import com.agentmemory.timetravel.BackupResult;
import com.agentmemory.timetravel.BackupService;
import com.agentmemory.timetravel.BootstrapResult;
import com.agentmemory.timetravel.BootstrapService;
import com.agentmemory.timetravel.Checkpoint;
import com.agentmemory.timetravel.RestorePageResult;
import com.agentmemory.timetravel.RestoreResult;
import com.agentmemory.timetravel.TimeTravelException;
import com.agentmemory.timetravel.TimeTravelService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Time-travel + backup/restore + bootstrap endpoints (issue #34). The thin Go CLI drives these; the
 * client never touches Postgres or the wiki directly (§2.1).
 *
 * <ul>
 *   <li>{@code GET  /checkpoints?limit=N} — recent wiki commits (time-travel points).</li>
 *   <li>{@code POST /restore-page} — restore one markdown file from a git revision and reindex it.</li>
 *   <li>{@code POST /backup} — write an online DB-only backup tarball (source stays writable).</li>
 *   <li>{@code POST /restore} — restore DB-only state from a tarball; live-process guarded (inv #9).</li>
 *   <li>{@code POST /bootstrap} — seed pages from a repo's history via one LLM pass.</li>
 * </ul>
 *
 * <p>Each service is injected via {@link ObjectProvider} so a DB-less / unconfigured context still
 * constructs the controller but answers {@code 503}. A {@link TimeTravelException} (unknown revision,
 * missing path, bad archive) maps to {@code 409 Conflict}; an {@link IllegalArgumentException} (bad
 * identity / path) to {@code 400}. A {@code restore} refused by the live-process guard is itself a
 * {@code 409} (the precondition — no live holder — failed), mirroring {@code /reset} (#33).
 */
@RestController
public class TimeTravelController {

    private static final Logger log = LoggerFactory.getLogger(TimeTravelController.class);

    private final ObjectProvider<TimeTravelService> timeTravel;
    private final ObjectProvider<BackupService> backup;
    private final ObjectProvider<BootstrapService> bootstrap;

    public TimeTravelController(
            ObjectProvider<TimeTravelService> timeTravel,
            ObjectProvider<BackupService> backup,
            ObjectProvider<BootstrapService> bootstrap) {
        this.timeTravel = timeTravel;
        this.backup = backup;
        this.bootstrap = bootstrap;
    }

    // --- time-travel -----------------------------------------------------------------------------

    @GetMapping("/checkpoints")
    public ResponseEntity<Map<String, Object>> checkpoints(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        TimeTravelService svc = timeTravel.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        List<Checkpoint> commits = svc.recentCheckpoints(limit);
        List<Map<String, Object>> items = new ArrayList<>(commits.size());
        for (Checkpoint c : commits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sha", c.sha());
            m.put("shortSha", c.shortSha());
            m.put("message", c.message());
            m.put("author", c.author());
            m.put("committedAt", c.committedAt().toString());
            items.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", items.size());
        body.put("checkpoints", items);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/restore-page")
    public ResponseEntity<Map<String, Object>> restorePage(@RequestBody RestorePageRequest req) {
        TimeTravelService svc = timeTravel.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            RestorePageResult r = svc.restorePage(
                    req.workspace(), req.project(), req.path(), req.from());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("workspace", r.identity().workspace().value());
            body.put("project", r.identity().project().value());
            body.put("path", r.identity().page().value());
            body.put("fromRev", r.fromRev());
            body.put("commitSha", r.commitSha());
            body.put("pagesIndexed", r.pagesIndexed());
            body.put("changed", r.changed());
            return ResponseEntity.ok(body);
        } catch (TimeTravelException e) {
            return conflict(e);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // --- backup / restore ------------------------------------------------------------------------

    @PostMapping("/backup")
    public ResponseEntity<Map<String, Object>> backup(@RequestBody BackupRequest req) {
        BackupService svc = backup.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            if (req.target() == null || req.target().isBlank()) {
                throw new IllegalArgumentException("target archive path is required");
            }
            BackupResult r = svc.backup(Path.of(req.target()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("archive", r.archive().toString());
            body.put("totalRows", r.totalRows());
            body.put("bytes", r.bytes());
            body.put("rowCounts", r.rowCounts());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    @PostMapping("/restore")
    public ResponseEntity<Map<String, Object>> restore(@RequestBody RestoreRequest req) {
        BackupService svc = backup.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            if (req.source() == null || req.source().isBlank()) {
                throw new IllegalArgumentException("source archive path is required");
            }
            RestoreResult r = svc.restore(Path.of(req.source()), req.force());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("performed", r.performed());
            body.put("reason", r.reason());
            body.put("liveHolderPid", r.liveHolderPid());
            body.put("totalRows", r.totalRows());
            body.put("rowCounts", r.rowCounts());
            // A refusal due to a live process is a 409 (the no-live-holder precondition failed).
            HttpStatus status = r.performed() ? HttpStatus.OK : HttpStatus.CONFLICT;
            if (!r.performed()) {
                log.warn("restore refused: {}", r.reason());
            }
            return ResponseEntity.status(status).body(body);
        } catch (TimeTravelException e) {
            return conflict(e);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // --- bootstrap -------------------------------------------------------------------------------

    @PostMapping("/bootstrap")
    public ResponseEntity<Map<String, Object>> bootstrap(@RequestBody BootstrapRequest req) {
        BootstrapService svc = bootstrap.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        try {
            if (req.repo() == null || req.repo().isBlank()) {
                throw new IllegalArgumentException("repo path is required");
            }
            BootstrapResult r = svc.bootstrap(req.workspace(), req.project(), Path.of(req.repo()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("workspace", r.target().workspace().value());
            body.put("project", r.target().project().value());
            body.put("performed", r.performed());
            body.put("reason", r.reason());
            body.put("pagesWritten", r.pagesWritten());
            HttpStatus status = r.performed() ? HttpStatus.OK : HttpStatus.CONFLICT;
            return ResponseEntity.status(status).body(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable",
                        "reason", "time-travel not configured (no datasource/wiki/llm)"));
    }

    private static ResponseEntity<Map<String, Object>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", "conflict", "reason", String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", "error", "reason", String.valueOf(e.getMessage())));
    }

    // --- request bodies --------------------------------------------------------------------------

    /** {@code POST /restore-page}: restore {@code workspace/project/path} from git revision {@code from}. */
    public record RestorePageRequest(String workspace, String project, String path, String from) {
    }

    /** {@code POST /backup}: write the DB-only backup tarball to {@code target}. */
    public record BackupRequest(String target) {
    }

    /** {@code POST /restore}: restore DB-only state from {@code source}; {@code force} bypasses inv #9. */
    public record RestoreRequest(String source, boolean force) {
    }

    /** {@code POST /bootstrap}: seed {@code workspace/project} pages from the repo at {@code repo}. */
    public record BootstrapRequest(String workspace, String project, String repo) {
    }
}
