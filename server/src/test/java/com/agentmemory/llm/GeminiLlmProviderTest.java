package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.config.ProviderAuth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real Gemini client against a loopback stub (no external network): the
 * {@code generateContent} request shape it builds (system hoisted into {@code systemInstruction},
 * {@code generationConfig.responseSchema} for invariant #7), how it parses {@code candidates[]} and
 * {@code usageMetadata}, the structured-output tolerant fallback, and HTTP/block error mapping.
 */
class GeminiLlmProviderTest {

    private static final String OK_RESPONSE = """
            {
              "candidates": [
                {"content": {"parts": [{"text": "{\\"ok\\":true}"}]}, "finishReason": "STOP"}
              ],
              "usageMetadata": {"promptTokenCount": 7, "candidatesTokenCount": 4},
              "modelVersion": "gemini-2.5-flash"
            }
            """;

    private static final JsonSchema SCHEMA = new JsonSchema("t",
            "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"]}");

    private static ProviderAuth auth(String baseUrl) {
        return new ProviderAuth("gemini", "k-test", baseUrl, null);
    }

    @Test
    void sendsStructuredRequestAndParsesReply() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            GeminiLlmProvider provider = new GeminiLlmProvider(auth(stub.baseUrl()));

            ChatResponse response = provider.chat(ChatRequest.structured(
                    List.of(ChatMessage.system("be terse"), ChatMessage.user("ping")), SCHEMA));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");
            assertThat(response.provider()).isEqualTo("gemini");
            assertThat(response.model()).isEqualTo("gemini-2.5-flash");
            assertThat(response.inputTokens()).isEqualTo(7);
            assertThat(response.outputTokens()).isEqualTo(4);

            String body = stub.lastRequestBody();
            // System hoisted into systemInstruction (not a contents turn); schema + json mime present.
            assertThat(body).contains("\"systemInstruction\"").contains("be terse");
            assertThat(body).contains("\"responseSchema\"");
            assertThat(body).contains("\"responseMimeType\":\"application/json\"");
            assertThat(body).contains("\"maxOutputTokens\"");
            // The path carries the model + the generateContent method.
            assertThat(stub.lastRequestPath()).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
            // The API key rides in the x-goog-api-key header.
            assertThat(stub.lastRequestHeader("x-goog-api-key")).isEqualTo("k-test");
        }
    }

    @Test
    void freeFormRequestOmitsResponseSchema() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            GeminiLlmProvider provider = new GeminiLlmProvider(auth(stub.baseUrl()));

            provider.chat(ChatRequest.text(ChatMessage.ofUser("hi")));

            assertThat(stub.lastRequestBody()).doesNotContain("responseSchema");
            assertThat(stub.lastRequestBody()).doesNotContain("responseMimeType");
        }
    }

    @Test
    void mapsAssistantRoleToModel() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            GeminiLlmProvider provider = new GeminiLlmProvider(auth(stub.baseUrl()));

            provider.chat(ChatRequest.text(List.of(
                    ChatMessage.user("q"), ChatMessage.assistant("a"), ChatMessage.user("q2"))));

            // Gemini uses role "model" for the assistant turn.
            assertThat(stub.lastRequestBody()).contains("\"role\":\"model\"");
        }
    }

    @Test
    void structuredOutputFallsBackWhenSchemaRejected() throws Exception {
        // First call (responseSchema) → 400 unsupported; second (json-only) → 200. The fallback must
        // retry and succeed, carrying the schema in the systemInstruction.
        try (StubHttpServer stub = StubHttpServer.startSequence(
                new StubHttpServer.Response(400,
                        "{\"error\":{\"message\":\"Invalid JSON payload: unknown name responseSchema\"}}"),
                new StubHttpServer.Response(200, OK_RESPONSE))) {
            GeminiLlmProvider provider = new GeminiLlmProvider(auth(stub.baseUrl()));

            ChatResponse response = provider.chat(ChatRequest.structured(
                    List.of(ChatMessage.user("ping")), SCHEMA));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");
            List<String> bodies = stub.requestBodies();
            assertThat(bodies).hasSize(2);
            // First attempt used responseSchema; the retry dropped it and put the schema in the prompt.
            assertThat(bodies.get(0)).contains("responseSchema");
            assertThat(bodies.get(1)).doesNotContain("responseSchema");
            assertThat(bodies.get(1)).contains("systemInstruction").contains("JSON Schema");
        }
    }

    @Test
    void blockedResponseBecomesPermanentException() throws Exception {
        String blocked = """
                {"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, blocked)) {
            GeminiLlmProvider provider = new GeminiLlmProvider(auth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("SAFETY")
                    .matches(e -> !((LlmException) e).isRetryable());
        }
    }

    @Test
    void http401IsPermanent() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(401, "{\"error\":\"bad key\"}")) {
            GeminiLlmProvider provider = new GeminiLlmProvider(auth(stub.baseUrl()));

            assertThatThrownBy(provider::probe)
                    .isInstanceOf(LlmException.class)
                    .matches(e -> !((LlmException) e).isRetryable(), "permanent")
                    .hasMessageContaining("401");
        }
    }

    @Test
    void http429IsRetryable() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(429, "{\"error\":\"slow down\"}")) {
            GeminiLlmProvider provider = new GeminiLlmProvider(auth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).isRetryable(), "retryable")
                    .hasMessageContaining("429");
        }
    }

    @Test
    void usesConfiguredModelOverDefault() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            ProviderAuth withModel = new ProviderAuth("gemini", "k", stub.baseUrl(), "gemini-2.5-pro");
            GeminiLlmProvider provider = new GeminiLlmProvider(withModel);

            assertThat(provider.model()).isEqualTo("gemini-2.5-pro");
            provider.chat(ChatRequest.text(ChatMessage.ofUser("x")));
            assertThat(stub.lastRequestPath()).contains("gemini-2.5-pro:generateContent");
        }
    }

    @Test
    void missingApiKeyFailsFast() {
        ProviderAuth auth = new ProviderAuth("gemini", null, "http://localhost:1", null);
        assertThatThrownBy(() -> new GeminiLlmProvider(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No API key configured");
    }
}
