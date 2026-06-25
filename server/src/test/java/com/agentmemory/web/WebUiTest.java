package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Serves the embedded {@code /web} browser for real over a {@code RANDOM_PORT} server and asserts it
 * is actually mounted (issue #36): the reference {@code index.html} loads, the bare {@code /web}
 * redirects to {@code /web/} (so the page's relative {@code ../api/v1} base resolves), and the surface
 * is read-only (a write verb is rejected). The static UI needs no database, so this uses the DB-less
 * context — a dormant/unmounted handler would 404 here.
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
class WebUiTest {

    @LocalServerPort int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(port);
    }

    @Test
    void referenceUiIndexIsServed() {
        HttpTestClient.Response r = http.get("/web/");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).contains("text/html");
        // The shipped reference UI — proves the real file is served, not a stub.
        assertThat(r.body()).contains("agent-memory");
        assertThat(r.body()).contains("read-only");
        // It targets the API at a relative path so it rides --base-path.
        assertThat(r.body()).contains("../api/v1/");
    }

    @Test
    void indexHtmlIsReachableByName() {
        HttpTestClient.Response r = http.get("/web/index.html");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("Content-Type")).contains("text/html");
    }

    @Test
    void bareWebRedirectsToTrailingSlash() {
        // 3xx with Location /web/ so the page's relative "../api/v1" resolves against /web/, not /.
        HttpTestClient.Response r = http.get("/web");
        assertThat(r.status()).isBetween(300, 399);
        assertThat(r.header("Location")).endsWith("/web/");
    }

    @Test
    void webIsReadOnlyWriteVerbsAreRejected() {
        // The static-resource handler serves GET/HEAD only; a POST/PUT/DELETE to a /web path must not
        // be accepted (no mutation surface here — auth hardening of non-GET is #38).
        assertThat(http.send("POST", "/web/index.html").status()).isGreaterThanOrEqualTo(400);
        assertThat(http.send("DELETE", "/web/index.html").status()).isGreaterThanOrEqualTo(400);
    }
}
