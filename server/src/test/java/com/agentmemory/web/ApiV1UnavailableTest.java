package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Proves the {@code /api/v1} controller still loads and answers {@code 503} in a deliberately DB-less
 * context (issue #35 robustness — same contract as {@code HookController}). The DataSource + Flyway
 * auto-configurations are excluded, so the DB-backed read beans are absent; the controller is
 * constructed via {@code ObjectProvider} and reports the read store as unavailable instead of the
 * context failing to start.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        })
class ApiV1UnavailableTest {

    @LocalServerPort int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(port);
    }

    @Test
    void endpointsReturn503WithoutADatabase() {
        assertThat(http.get("/api/v1/workspaces").status()).isEqualTo(503);
        assertThat(http.get("/api/v1/graph").status()).isEqualTo(503);
    }
}
