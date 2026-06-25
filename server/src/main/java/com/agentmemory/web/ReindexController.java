package com.agentmemory.web;

import com.agentmemory.reindex.ReindexMode;
import com.agentmemory.reindex.ReindexOptions;
import com.agentmemory.reindex.ReindexReport;
import com.agentmemory.reindex.ReindexService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * {@code POST /reindex}: rebuild the Postgres index from the wiki source of truth (issue #14;
 * ARCHITECTURE §2.3, DD-002). The thin Go CLI ({@code agent-memory reindex}) calls this; it is the
 * only way to drive a reindex (the client never touches Postgres or the wiki directly, §2.1).
 *
 * <p>The request selects {@code full} vs {@code incremental}, the incremental {@code since} git ref,
 * and whether to {@code reembed}. The response is the {@link ReindexReport} (what was rebuilt + any
 * skipped files), {@code 200 OK} on success.
 *
 * <p>{@link ReindexService} is injected via {@link ObjectProvider} because it exists only when the
 * store + wiki layers are wired (a {@code DataSource} is configured); a DB-less context still
 * constructs this controller but answers {@code 503 Service Unavailable}, mirroring
 * {@link HookController}.
 */
@RestController
public class ReindexController {

    private static final Logger log = LoggerFactory.getLogger(ReindexController.class);

    private final ObjectProvider<ReindexService> reindex;

    public ReindexController(ObjectProvider<ReindexService> reindex) {
        this.reindex = reindex;
    }

    /**
     * Run a reindex.
     *
     * @param request the (optional-fielded) reindex request; an absent/blank {@code mode} defaults to
     *                {@code full}.
     * @return 200 with the report, 400 if {@code mode} is an unknown value, 503 if reindex is unwired.
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@RequestBody(required = false) Request request) {
        ReindexService svc = reindex.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "unavailable",
                            "reason", "reindex not configured (no datasource/wiki)"));
        }
        Request req = request == null ? new Request(null, null, false) : request;
        ReindexMode mode;
        try {
            mode = parseMode(req.mode());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "error", "reason", e.getMessage()));
        }

        ReindexOptions options = new ReindexOptions(mode, req.since(), req.reembed());
        ReindexReport report = svc.reindex(options);
        log.info("reindex via API: {}", report);
        return ResponseEntity.ok(toBody(report));
    }

    private static ReindexMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return ReindexMode.FULL;
        }
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> ReindexMode.FULL;
            case "incremental", "inc" -> ReindexMode.INCREMENTAL;
            default -> throw new IllegalArgumentException(
                    "unknown reindex mode '" + mode + "' (expected 'full' or 'incremental')");
        };
    }

    private static Map<String, Object> toBody(ReindexReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", report.mode().name().toLowerCase(Locale.ROOT));
        body.put("filesScanned", report.filesScanned());
        body.put("pagesIndexed", report.pagesIndexed());
        body.put("pagesDeleted", report.pagesDeleted());
        body.put("linksWritten", report.linksWritten());
        body.put("linksResolved", report.linksResolved());
        List<Map<String, String>> skips = report.skipped().stream()
                .map(s -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("path", s.path());
                    m.put("reason", s.reason());
                    return m;
                })
                .toList();
        body.put("skipped", skips);
        return body;
    }

    /**
     * Reindex request body. All fields optional.
     *
     * @param mode    {@code "full"} (default) or {@code "incremental"}.
     * @param since   incremental git ref to diff from (e.g. {@code "HEAD~1"} or a sha); null ⇒ HEAD~1.
     * @param reembed whether to (re)compute embeddings (default false; no-op without an embedder).
     */
    public record Request(String mode, String since, boolean reembed) {
    }
}
