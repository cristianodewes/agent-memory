package com.agentmemory.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tiny JDK-{@link HttpClient} wrapper for the {@code /api/v1} contract tests. Spring Boot 4 removed
 * {@code TestRestTemplate} and the project does not pull in WebFlux (so no {@code WebTestClient}); the
 * built-in HTTP client is enough to drive a {@code RANDOM_PORT} server and assert status + JSON.
 */
final class HttpTestClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // Do not auto-follow redirects: relocation tests assert a bare path is gone (404), not
            // silently followed somewhere else.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String base;

    HttpTestClient(int port) {
        this.base = "http://localhost:" + port;
    }

    /** A GET response: HTTP status + raw body. */
    record Response(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
        boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }

    Response get(String path) {
        try {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(base + path)).GET()
                            .timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("GET " + path + " failed", e);
        }
    }

    /** POST a JSON-serializable body. */
    Response postJson(String path, Object body) {
        try {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create(base + path))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                            .timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("POST " + path + " failed", e);
        }
    }

    /** GET and require a 2xx, returning the parsed JSON (fails loudly otherwise). */
    JsonNode getJsonOk(String path) {
        Response r = get(path);
        if (!r.is2xx()) {
            throw new AssertionError("GET " + path + " -> " + r.status() + " : " + r.body());
        }
        return r.json();
    }
}
