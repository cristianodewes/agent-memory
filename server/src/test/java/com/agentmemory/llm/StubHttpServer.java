package com.agentmemory.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
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

    /** The base URL ({@code http://127.0.0.1:<port>}) to hand to a provider via {@code ProviderAuth.baseUrl}. */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The most recent request body the stub received, for asserting request shape. */
    String lastRequestBody() {
        return lastRequestBody.get();
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
