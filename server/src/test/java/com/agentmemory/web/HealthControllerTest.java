package com.agentmemory.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HealthControllerTest {

    @Test
    void healthzReportsOk() {
        var body = new HealthController().healthz();
        assertEquals("ok", body.get("status"));
        assertEquals("agent-memory-server", body.get("service"));
    }
}
