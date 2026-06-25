package com.agentmemory.llm;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A tiny loopback HTTP stub for exercising the real provider clients without any external network
 * call. It binds {@code 127.0.0.1} on an OS-chosen port and lets a test script the status + body and
 * capture the request body the provider sent — enough to assert request shape (system hoisting,
 * {@code output_config}) and response parsing/error mapping while honouring the issue's "tests must
 * not make real network calls" rule.
 *
 * <p>Loopback-only and per-test (started in a try-with-resources), so the suite stays hermetic.
 */
final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<Headers> lastRequestHeaders = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final java.util.List<String> bodies =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private StubHttpServer(HttpServer server) {
        this.server = server;
    }

    /**
     * Start a stub that responds to every request with {@code status} and the body returned by
     * {@code responder} (given the captured request body).
     */
    static StubHttpServer start(int status, Function<String, String> responder) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        StubHttpServer stub = new StubHttpServer(server);
        server.createContext("/", (HttpExchange exchange) -> {
            String requestBody = readBody(exchange);
            stub.lastRequestBody.set(requestBody);
            stub.lastRequestHeaders.set(exchange.getRequestHeaders());
            URI uri = exchange.getRequestURI();
            stub.lastRequestPath.set(uri == null ? null : uri.getPath());
            byte[] response = responder.apply(requestBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        return stub;
    }

    /** Convenience: always reply with a fixed body. */
    static StubHttpServer start(int status, String body) throws IOException {
        return start(status, req -> body);
    }

    /**
     * Start a stub that replays a fixed sequence of {@code (status, body)} responses, one per request
     * (the last entry is repeated if more requests arrive). Lets a test drive the structured-output
     * fallback: first call returns {@code 400} (json_schema unsupported), second returns {@code 200}.
     */
    static StubHttpServer startSequence(Response... responses) throws IOException {
        if (responses.length == 0) {
            throw new IllegalArgumentException("at least one scripted response is required");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        StubHttpServer stub = new StubHttpServer(server);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", (HttpExchange exchange) -> {
            String requestBody = readBody(exchange);
            int idx = Math.min(calls.getAndIncrement(), responses.length - 1);
            stub.bodies.add(requestBody);
            stub.lastRequestBody.set(requestBody);
            stub.lastRequestHeaders.set(exchange.getRequestHeaders());
            URI uri = exchange.getRequestURI();
            stub.lastRequestPath.set(uri == null ? null : uri.getPath());
            Response r = responses[idx];
            byte[] response = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(r.status(), response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        return stub;
    }

    /** A single scripted {@code (status, body)} reply for {@link #startSequence}. */
    record Response(int status, String body) {}

    /** The base URL ({@code http://127.0.0.1:<port>}) to hand to a provider via {@code ProviderAuth.baseUrl}. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The most recent request body the stub received, for asserting request shape. */
    String lastRequestBody() {
        return lastRequestBody.get();
    }

    /**
     * The first value of request header {@code name} on the most recent request, or {@code null} if
     * the header was absent — for asserting auth-header presence/omission (keyless engines).
     */
    String lastRequestHeader(String name) {
        Headers headers = lastRequestHeaders.get();
        return headers == null ? null : headers.getFirst(name);
    }

    /** The path of the most recent request (e.g. {@code /v1/chat/completions}), for URL assertions. */
    String lastRequestPath() {
        return lastRequestPath.get();
    }

    /** All request bodies captured so far, in order — for asserting a multi-call fallback sequence. */
    java.util.List<String> requestBodies() {
        return java.util.List.copyOf(bodies);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
