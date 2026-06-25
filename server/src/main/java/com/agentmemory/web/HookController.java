package com.agentmemory.web;

import com.agentmemory.core.ActorResolver;
import com.agentmemory.hooks.HookPayload;
import com.agentmemory.hooks.IngestService;
import com.agentmemory.hooks.IngestStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The capture front door (ARCHITECTURE §2.2, §3.1; issue #8): {@code POST /hook} for one event and
 * {@code POST /hook/batch} for a spool drain. The handler is non-blocking — it validates + sanitizes
 * and hands off to {@link IngestService}'s bounded queue, returning immediately.
 *
 * <h2>Status codes</h2>
 * <ul>
 *   <li><strong>202 Accepted</strong> — the event was enqueued for the single writer (the normal
 *       fire-and-forget path; never wait on the store).</li>
 *   <li><strong>429 Too Many Requests</strong> — the bounded ingest queue is saturated (invariant
 *       #5); the client should retry from its spool later.</li>
 *   <li><strong>400 Bad Request</strong> — the (single) payload was malformed.</li>
 * </ul>
 *
 * <h2>Batch partial-accept</h2>
 * A batch is read as a JSON array of raw nodes and each element is parsed + ingested independently,
 * so a single malformed item is recorded against that item only while the rest are still processed —
 * the documented prior-art "batch drain stalls on one bad event" bug, designed out. The batch
 * response carries per-item statuses plus counts; its top-line code is 202 if anything was accepted,
 * 429 if nothing was accepted but the queue was saturating, else 400.
 *
 * <p>The {@link IngestService} is injected via an {@link ObjectProvider} because it only exists when
 * the store layer is wired (it needs a {@code DataSource}). A deliberately DB-less context (the
 * web/config smoke test) still constructs this controller, but {@code /hook} then answers
 * {@code 503 Service Unavailable} rather than the context failing to start.
 */
@RestController
public class HookController {

    private static final Logger log = LoggerFactory.getLogger(HookController.class);

    private final ObjectProvider<IngestService> ingest;
    private final ObjectMapper mapper;
    private final ActorResolver actors;

    /**
     * @param ingest the ingest pipeline (present only when the store layer is wired).
     * @param mapper the JSON mapper, used to parse batch items one at a time.
     * @param actors resolves the authenticated user to attribute captures to (issue #39). Injected via
     *     an {@link ObjectProvider} so a context without the security auto-config (a DB-less/web slice)
     *     still constructs the controller; it then falls back to {@link ActorResolver#NONE} (no actor).
     */
    public HookController(
            ObjectProvider<IngestService> ingest,
            ObjectMapper mapper,
            ObjectProvider<ActorResolver> actors) {
        this.ingest = ingest;
        this.mapper = mapper;
        this.actors = actors.getIfAvailable(() -> ActorResolver.NONE);
    }

    /**
     * Ingest a single hook event.
     *
     * @param payload the parsed hook payload (a malformed body fails deserialization → 400).
     * @return 202 if enqueued, 429 if the queue is saturated, 503 if ingest is unavailable.
     */
    @PostMapping("/hook")
    public ResponseEntity<Map<String, Object>> hook(@RequestBody HookPayload payload) {
        IngestService svc = ingest.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        // Resolve the actor here, on the request thread — the async write worker has no security context.
        IngestStatus status = svc.ingest(payload, actors.currentActor());
        return ResponseEntity.status(httpStatusFor(status)).body(Map.of("status", label(status)));
    }

    /**
     * Ingest a batch (a spool drain). Each item is parsed and ingested independently.
     *
     * @param items the raw JSON array of hook payloads.
     * @return a per-item report with counts; top-line 202/429/400 per the class doc.
     */
    @PostMapping("/hook/batch")
    public ResponseEntity<Map<String, Object>> hookBatch(@RequestBody List<JsonNode> items) {
        IngestService svc = ingest.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        int accepted = 0;
        int throttled = 0;
        int invalid = 0;
        List<Map<String, Object>> results = new ArrayList<>(items.size());

        // One drain is one authenticated caller: resolve the actor once, on the request thread.
        String actor = actors.currentActor();
        for (int i = 0; i < items.size(); i++) {
            IngestStatus status;
            try {
                HookPayload payload = mapper.treeToValue(items.get(i), HookPayload.class);
                status = svc.ingest(payload, actor);
            } catch (RuntimeException e) {
                // A bad item must not abort the batch (prior-art stall bug): record and continue.
                log.debug("batch item {} rejected: {}", i, e.toString());
                status = IngestStatus.INVALID;
            }
            switch (status) {
                case ACCEPTED -> accepted++;
                case THROTTLED -> throttled++;
                case INVALID -> invalid++;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            item.put("status", label(status));
            results.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", items.size());
        body.put("accepted", accepted);
        body.put("throttled", throttled);
        body.put("invalid", invalid);
        body.put("results", results);
        return ResponseEntity.status(batchStatus(accepted, throttled, invalid)).body(body);
    }

    /** 503 when the store/ingest layer is not wired (e.g. a DB-less context). */
    private static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable", "reason", "ingest store not configured"));
    }

    /** Single-event status → HTTP code. */
    private static HttpStatus httpStatusFor(IngestStatus status) {
        return switch (status) {
            case ACCEPTED -> HttpStatus.ACCEPTED; // 202
            case THROTTLED -> HttpStatus.TOO_MANY_REQUESTS; // 429
            case INVALID -> HttpStatus.BAD_REQUEST; // 400
        };
    }

    /**
     * Batch top-line code: 202 if anything was accepted; otherwise 429 if the queue was saturating
     * (so the client backs off and retries the whole drain); otherwise 400 (everything was bad).
     */
    private static HttpStatus batchStatus(int accepted, int throttled, int invalid) {
        if (accepted > 0) {
            return HttpStatus.ACCEPTED;
        }
        if (throttled > 0) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (invalid > 0) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.ACCEPTED; // empty batch: nothing to do
    }

    private static String label(IngestStatus status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }
}
