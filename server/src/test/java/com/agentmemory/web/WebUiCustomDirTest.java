package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the {@code --web-ui-dir} knob (issue #36 acceptance): when
 * {@code agent-memory.server.web-ui-dir} points at a filesystem directory, {@code /web} serves that
 * custom SPA build instead of the bundled reference UI. A marker file in the temp dir is fetched back
 * over the live server, end-to-end.
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
class WebUiCustomDirTest {

    @TempDir
    static Path customUiDir;

    private static final String MARKER = "CUSTOM-SPA-BUILD-MARKER";

    @BeforeAll
    static void writeCustomUi() throws IOException {
        Files.writeString(customUiDir.resolve("index.html"),
                "<!doctype html><title>custom</title><body>" + MARKER + "</body>");
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("agent-memory.server.web-ui-dir", () -> customUiDir.toString());
    }

    @LocalServerPort int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(port);
    }

    @Test
    void customWebUiDirIsServedInsteadOfTheBundledUi() {
        HttpTestClient.Response index = http.get("/web/index.html");
        assertThat(index.status()).isEqualTo(200);
        assertThat(index.body()).contains(MARKER);
        // And the bundled reference UI's distinctive marker is NOT what gets served.
        assertThat(index.body()).doesNotContain("../api/v1/");

        // The directory request still resolves to the custom index via the forward.
        HttpTestClient.Response dir = http.get("/web/");
        assertThat(dir.status()).isEqualTo(200);
        assertThat(dir.body()).contains(MARKER);
    }
}
