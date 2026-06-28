package com.agentmemory.web;

import com.agentmemory.llmrecall.RecallInjection;
import com.agentmemory.llmrecall.RecallMetrics;
import com.agentmemory.mcp.ScopeResolver;
import com.agentmemory.recall.Scope;
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
 * {@code POST /recall/inject}: return the concise, relevance-gated memory block a
 * {@code UserPromptSubmit} hook injects into the agent's context (issue #21). The thin Go client posts
 * the user's prompt (and optionally an explicit {@code workspace}/{@code project}); the server runs
 * LLM-assisted recall, curates the result ({@link RecallInjection}), and returns a bounded markdown
 * block ready to paste — or an empty block when nothing clears the relevance gate, so the hook injects
 * nothing on a low-signal prompt.
 *
 * <p>Scope defaults to the most recently active project (DD-003) via {@link ScopeResolver}, the same
 * resolution the MCP read tools use; passing {@code workspace}+{@code project} overrides it.
 *
 * <p>{@link RecallInjection} and {@link ScopeResolver} exist only when the store/recall layers are
 * wired (a {@code DataSource} is configured), so they are injected via {@link ObjectProvider}: a
 * DB-less context still constructs this controller but answers {@code 503 Service Unavailable},
 * mirroring {@link ReindexController} and {@link HookController}.
 */
@RestController
public class RecallInjectionController {

    private static final Logger log = LoggerFactory.getLogger(RecallInjectionController.class);

    private final ObjectProvider<RecallInjection> injection;
    private final ObjectProvider<ScopeResolver> scopes;
    private final ObjectProvider<RecallMetrics> metrics;

    public RecallInjectionController(
            ObjectProvider<RecallInjection> injection,
            ObjectProvider<ScopeResolver> scopes,
            ObjectProvider<RecallMetrics> metrics) {
        this.injection = injection;
        this.scopes = scopes;
        this.metrics = metrics;
    }

    /**
     * Build the injection block for a prompt.
     *
     * @param request the prompt plus optional explicit scope.
     * @return 200 with {@code {scope, hits, text}}; 400 on a missing prompt or a half-specified scope;
     *     503 when recall is not wired.
     */
    @PostMapping("/recall/inject")
    public ResponseEntity<Map<String, Object>> inject(@RequestBody(required = false) Request request) {
        RecallInjection svc = injection.getIfAvailable();
        ScopeResolver resolver = scopes.getIfAvailable();
        if (svc == null || resolver == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "unavailable",
                            "reason", "recall injection not configured (no datasource)"));
        }
        if (request == null || request.prompt() == null || request.prompt().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "reason", "missing required 'prompt'"));
        }

        Scope scope;
        try {
            // ScopeResolver reads workspace/project from a generic arg map (the MCP shape); adapt the
            // typed request fields into it. Null values are simply "not provided" → default scope.
            Map<String, Object> args = new LinkedHashMap<>();
            if (request.workspace() != null) {
                args.put("workspace", request.workspace());
            }
            if (request.project() != null) {
                args.put("project", request.project());
            }
            scope = resolver.resolve(args);
        } catch (ScopeResolver.ScopeUnresolvedException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "reason", "scope: " + e.getMessage()));
        }

        // Time the end-to-end injection (search + gate + render) so the #130 "p50/p95 ≤ 3s" criterion
        // is measurable at the endpoint level (issue #130 follow-up). Recorded on both the success and
        // the degrade-to-empty path, so a failing recall is still observed.
        long startNanos = System.nanoTime();
        RecallInjection.Result result;
        try {
            result = svc.inject(scope, request.prompt());
        } catch (RuntimeException e) {
            // A recall failure must not break the prompt submission; report it but degrade to empty.
            log.warn("recall injection failed for {}/{}: {}",
                    scope.workspaceSlug(), scope.projectSlug(), e.toString());
            result = RecallInjection.Result.empty();
        }
        RecallMetrics recallMetrics = metrics.getIfAvailable();
        if (recallMetrics != null) {
            recallMetrics.recordInject(System.nanoTime() - startNanos, result.hits());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", Map.of("workspace", scope.workspaceSlug(), "project", scope.projectSlug()));
        body.put("hits", result.hits());
        body.put("text", result.text());
        return ResponseEntity.ok(body);
    }

    /**
     * The injection request body.
     *
     * @param prompt    the user's prompt to find relevant memory for (required).
     * @param workspace explicit workspace slug, or null to use the most recently active project.
     * @param project   explicit project slug, or null to use the most recently active project.
     */
    public record Request(String prompt, String workspace, String project) {}
}
