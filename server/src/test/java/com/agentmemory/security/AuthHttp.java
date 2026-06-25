package com.agentmemory.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * A header-capable JDK-{@link HttpClient} helper for the issue #38 auth tests: it can attach an
 * {@code Authorization} (Bearer or Basic) header and arbitrary headers (e.g. {@code Origin},
 * {@code Host}) so a test can drive the real {@code RANDOM_PORT} server like a CLI client or a browser.
 * Redirects are not followed so a {@code 401}/{@code 403}/{@code 200} is asserted as returned.
 */
final class AuthHttp {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String base;

    AuthHttp(int port) {
        this.base = "http://localhost:" + port;
    }

    record Resp(int status, String body, java.net.http.HttpHeaders headers) {
        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /** Build a Basic {@code Authorization} value for {@code user:password}. */
    static String basic(String user, String password) {
        String raw = user + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Bearer {@code Authorization} value for {@code token}. */
    static String bearer(String token) {
        return "Bearer " + token;
    }

    /**
     * Send {@code method} to {@code path} with optional header pairs ({@code name, value, name, value,
     * ...}) and no body. Use the {@code static} helpers for the {@code Authorization} value.
     */
    Resp send(String method, String path, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15));
            if ("GET".equals(method)) {
                b.GET();
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            for (int i = 0; i + 1 < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Resp(r.statusCode(), r.body(), r.headers());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(method + " " + path + " failed", e);
        }
    }

    Resp get(String path, String... headers) {
        return send("GET", path, headers);
    }
}
