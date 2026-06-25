package com.agentmemory.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A thin JSON-over-HTTP helper shared by the real provider clients ({@code AnthropicLlmProvider},
 * {@code VoyageEmbedder}). It centralizes the JDK {@link HttpClient} setup, request timeouts, and
 * the mapping from HTTP status codes to {@link LlmException} so each provider only describes its
 * endpoint, headers and payload shape — keeping the provider matrix small and uniform for issue #40.
 *
 * <p>Uses the JDK {@link java.net.http.HttpClient} (no third-party HTTP dependency) and the
 * Spring-managed Jackson 3 ({@code tools.jackson}) {@link JsonMapper}. Not a Spring bean — providers
 * own their instance, constructed after their typed auth is resolved (invariant #14).
 */
final class HttpJsonClient {

    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration requestTimeout;

    HttpJsonClient(Duration connectTimeout, Duration requestTimeout) {
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.json = JsonMapper.builder().build();
        this.requestTimeout = requestTimeout;
    }

    /** Serialize {@code body} to a JSON string. */
    String write(Object body) {
        return json.writeValueAsString(body);
    }

    /** Parse {@code raw} into a {@link JsonNode} tree, mapping malformed JSON to a permanent failure. */
    JsonNode parse(String raw) {
        try {
            return json.readTree(raw);
        } catch (JacksonException e) {
            throw LlmException.permanent("provider returned malformed JSON: " + e.getMessage(), e);
        }
    }

    /** The shared mapper, for providers that need to build request trees or validate replies. */
    ObjectMapper mapper() {
        return json;
    }

    /**
     * POST a JSON body and return the parsed response tree on a 2xx. Non-2xx responses become an
     * {@link LlmException}: {@code 408/429/5xx} are retryable, everything else permanent. The
     * response body is truncated into the message so an operator can see the provider's error
     * without it ballooning the log.
     *
     * @param uri          the endpoint.
     * @param headers      request headers (auth, content-type, api-version, …).
     * @param body         the request payload object, serialized to JSON.
     * @param providerName provider label for error messages.
     * @return the parsed JSON response tree.
     * @throws LlmException on transport failure or a non-2xx status.
     */
    JsonNode postJson(URI uri, Map<String, String> headers, Object body, String providerName) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body)));
        headers.forEach(builder::header);

        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Connection refused, DNS failure, read timeout — all transient from our side.
            throw LlmException.retryable(
                    providerName + " request failed (transport error): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.retryable(providerName + " request interrupted", e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parse(response.body());
        }

        boolean retryable = status == 408 || status == 429 || status >= 500;
        String detail = truncate(response.body());
        String message = providerName + " request failed with HTTP " + status + ": " + detail;
        throw new LlmException(message, null, retryable);
    }

    private static String truncate(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String trimmed = body.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…(truncated)" : trimmed;
    }
}
