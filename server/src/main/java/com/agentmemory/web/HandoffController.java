package com.agentmemory.web;

import com.agentmemory.core.Handoff;
import com.agentmemory.core.SessionId;
import com.agentmemory.handoff.HandoffException;
import com.agentmemory.handoff.HandoffService;
import com.agentmemory.recall.Scope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for typed handoffs (issue #22; ARCHITECTURE §3.4). Lets a client that has no
 * {@code session-end} hook open a handoff before exit, and accept/cancel one:
 *
 * <ul>
 *   <li>{@code POST /handoff} — begin: synthesize and open an LLM-written handoff for the session.</li>
 *   <li>{@code POST /handoff/accept} — consume the latest open handoff (single-use; 200 with the
 *       handoff, or 204 when there is none).</li>
 *   <li>{@code POST /handoff/cancel} — expire the latest open handoff (200 with it, or 204 when none).</li>
 * </ul>
 *
 * <p>{@link HandoffService} is injected via an {@link ObjectProvider} because it exists only when the
 * store + LLM layers are wired; a DB-less context still constructs this controller but answers
 * {@code 503 Service Unavailable}, mirroring {@link HookController}/{@link ReindexController}.
 */
@RestController
public class HandoffController {

    private static final Logger log = LoggerFactory.getLogger(HandoffController.class);

    private final ObjectProvider<HandoffService> handoffs;

    public HandoffController(ObjectProvider<HandoffService> handoffs) {
        this.handoffs = handoffs;
    }

    /**
     * Begin (synthesize + open) a handoff for a session.
     *
     * @param request workspace + project + sessionId; all required.
     * @return 200 with the opened handoff, 400 on a bad request, 502 if the LLM reply was unusable,
     *     503 if handoffs are unwired.
     */
    @PostMapping("/handoff")
    public ResponseEntity<Map<String, Object>> begin(@RequestBody(required = false) BeginRequest request) {
        HandoffService svc = handoffs.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        if (request == null) {
            return badRequest("request body required (workspace, project, sessionId)");
        }
        Scope scope;
        SessionId session;
        try {
            scope = Scope.of(require(request.workspace(), "workspace"), require(request.project(), "project"));
            session = SessionId.of(require(request.sessionId(), "sessionId"));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            Handoff handoff = svc.begin(scope, session);
            return ResponseEntity.ok(toBody(handoff));
        } catch (HandoffException e) {
            log.warn("handoff begin failed (LLM reply): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", "error", "reason", e.getMessage()));
        }
    }

    /** Accept (consume) the latest open handoff for a project. Single-use. */
    @PostMapping("/handoff/accept")
    public ResponseEntity<Map<String, Object>> accept(@RequestBody(required = false) ScopeRequest request) {
        return scopeMutation(request, svc -> svc::accept);
    }

    /** Cancel (expire) the latest open handoff for a project. */
    @PostMapping("/handoff/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody(required = false) ScopeRequest request) {
        return scopeMutation(request, svc -> svc::cancel);
    }

    // --- shared --------------------------------------------------------------------------------

    private ResponseEntity<Map<String, Object>> scopeMutation(
            ScopeRequest request, java.util.function.Function<HandoffService,
                    java.util.function.Function<Scope, Optional<Handoff>>> op) {
        HandoffService svc = handoffs.getIfAvailable();
        if (svc == null) {
            return unavailable();
        }
        if (request == null) {
            return badRequest("request body required (workspace, project)");
        }
        Scope scope;
        try {
            scope = Scope.of(require(request.workspace(), "workspace"), require(request.project(), "project"));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        Optional<Handoff> result = op.apply(svc).apply(scope);
        return result.<ResponseEntity<Map<String, Object>>>map(h -> ResponseEntity.ok(toBody(h)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable", "reason", "handoffs not configured (no datasource)"));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String reason) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("status", "error", "reason", reason));
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required '" + field + "'");
        }
        return value;
    }

    /** Serialize a handoff to the response body (the wire shape clients read). */
    static Map<String, Object> toBody(Handoff h) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", h.id().value().toString());
        body.put("workspace", h.identity().workspace().value());
        body.put("project", h.identity().project().value());
        body.put("fromSession", h.fromSession().value().toString());
        body.put("status", h.status().wire());
        body.put("summary", h.summary());
        body.put("openQuestions", List.copyOf(h.openQuestions()));
        body.put("nextSteps", List.copyOf(h.nextSteps()));
        body.put("createdAt", h.createdAt().toString());
        body.put("acceptedAt", h.acceptedAt() == null ? null : h.acceptedAt().toString());
        return body;
    }

    /** {@code POST /handoff} body: the project + the session to summarize. */
    public record BeginRequest(String workspace, String project, String sessionId) {}

    /** {@code POST /handoff/accept|cancel} body: the project to operate on. */
    public record ScopeRequest(String workspace, String project) {}
}
