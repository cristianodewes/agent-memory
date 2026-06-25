package com.agentmemory.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal liveness endpoint for the scaffolding milestone (M0 / issue #1).
 *
 * <p>Returns 200 with a tiny JSON body so the docker-compose smoke check and
 * local dev have a stable probe. Richer health (DB, LLM provider) arrives with
 * issues #4 and #6.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of(
                "status", "ok",
                "service", "agent-memory-server");
    }
}
