package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.config.ProviderAuth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real OpenAI-compatible client against a loopback stub (no external network): the
 * Chat Completions request shape it builds (messages[] with inline system, {@code response_format}
 * for invariant #7), the {@code json_schema}→{@code json_object} tolerant fallback, how it parses
 * the {@code choices[].message.content} reply and usage, the optional {@code Authorization} header
 * (keyless engines), base-URL resolution, and how it maps refusals / HTTP errors to
 * {@link LlmException}.
 */
class OpenAiCompatLlmProviderTest {

    private static final String OK_RESPONSE = """
            {
              "id": "chatcmpl-1",
              "object": "chat.completion",
              "model": "gpt-5.5",
              "choices": [
                {"index": 0, "finish_reason": "stop",
                 "message": {"role": "assistant", "content": "{\\"ok\\":true}"}}
              ],
              "usage": {"prompt_tokens": 11, "completion_tokens": 5}
            }
            """;

    private static final JsonSchema OK_SCHEMA = new JsonSchema("t",
            "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                    + "\"required\":[\"ok\"],\"additionalProperties\":false}");

    /** {@code openai-compat} auth pointed at the stub, with an api key. */
    private static ProviderAuth compatAuth(String baseUrl) {
        return new ProviderAuth(OpenAiCompatLlmProvider.COMPAT_KEY, "sk-test", baseUrl, null);
    }

    private static OpenAiCompatLlmProvider compat(ProviderAuth auth) {
        return new OpenAiCompatLlmProvider(OpenAiCompatLlmProvider.COMPAT_KEY, auth);
    }

    // --- happy path + request shaping ----------------------------------------------------------

    @Test
    void sendsJsonSchemaRequestAndParsesReply() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            ChatResponse response = provider.chat(ChatRequest.structured(
                    List.of(ChatMessage.system("be terse"), ChatMessage.user("ping")), OK_SCHEMA));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");
            assertThat(response.provider()).isEqualTo("openai-compat");
            assertThat(response.model()).isEqualTo("gpt-5.5");
            assertThat(response.inputTokens()).isEqualTo(11);
            assertThat(response.outputTokens()).isEqualTo(5);

            // Request shape: system stays inline in messages[] (OpenAI, not hoisted); strict json_schema.
            String body = stub.lastRequestBody();
            assertThat(body).contains("\"role\":\"system\"").contains("\"content\":\"be terse\"");
            assertThat(body).contains("\"response_format\"")
                    .contains("\"type\":\"json_schema\"")
                    .contains("\"strict\":true")
                    .contains("\"name\":\"t\"");
            assertThat(body).contains("\"max_tokens\"");
            // Endpoint must be the OpenAI Chat Completions path.
            assertThat(stub.lastRequestPath()).endsWith("/chat/completions");
        }
    }

    @Test
    void freeFormRequestOmitsResponseFormat() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, plainReply("hello"))) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            ChatResponse r = provider.chat(ChatRequest.text(ChatMessage.ofUser("hi")));

            assertThat(r.text()).isEqualTo("hello");
            assertThat(stub.lastRequestBody()).doesNotContain("response_format");
        }
    }

    // --- auth header present / absent (keyless local engine) -----------------------------------

    @Test
    void sendsBearerAuthorizationWhenKeyPresent() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            provider.chat(ChatRequest.text(ChatMessage.ofUser("x")));

            assertThat(stub.lastRequestHeader("Authorization")).isEqualTo("Bearer sk-test");
        }
    }

    @Test
    void omitsAuthorizationHeaderForKeylessEngine() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            // No api key — a local engine like Ollama.
            ProviderAuth keyless =
                    new ProviderAuth(OpenAiCompatLlmProvider.COMPAT_KEY, null, stub.baseUrl(), "llama3");
            OpenAiCompatLlmProvider provider = compat(keyless);

            provider.chat(ChatRequest.text(ChatMessage.ofUser("x")));

            assertThat(stub.lastRequestHeader("Authorization")).isNull();
            assertThat(provider.model()).isEqualTo("llama3");
        }
    }

    // --- structured-output tolerant fallback ---------------------------------------------------

    @Test
    void fallsBackToJsonObjectWhenSchemaUnsupported() throws Exception {
        String unsupported =
                "{\"error\":{\"message\":\"response_format.type 'json_schema' is not supported\"}}";
        try (StubHttpServer stub = StubHttpServer.startSequence(
                new StubHttpServer.Response(400, unsupported),
                new StubHttpServer.Response(200, OK_RESPONSE))) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            ChatResponse response = provider.chat(ChatRequest.structured(
                    List.of(ChatMessage.user("ping")), OK_SCHEMA));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");

            List<String> bodies = stub.requestBodies();
            assertThat(bodies).hasSize(2);
            // First attempt: strict json_schema.
            assertThat(bodies.get(0)).contains("\"type\":\"json_schema\"");
            // Fallback attempt: json_object + schema injected into a system turn.
            assertThat(bodies.get(1)).contains("\"type\":\"json_object\"")
                    .doesNotContain("\"type\":\"json_schema\"");
            // The schema is injected into the system turn (JSON-escaped inside the message string).
            assertThat(bodies.get(1)).contains("JSON Schema").contains("properties");
        }
    }

    @Test
    void doesNotFallBackOnGenuineBadRequest() throws Exception {
        // A 400 that is NOT about response_format must surface, not trigger a fallback retry.
        String badReq = "{\"error\":{\"message\":\"model 'nope' does not exist\"}}";
        try (StubHttpServer stub = StubHttpServer.startSequence(
                new StubHttpServer.Response(400, badReq),
                new StubHttpServer.Response(200, OK_RESPONSE))) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.structured(
                    List.of(ChatMessage.user("ping")), OK_SCHEMA)))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> !((LlmException) e).isRetryable(), "permanent")
                    .hasMessageContaining("400");

            assertThat(stub.requestBodies()).hasSize(1); // no fallback retry
        }
    }

    @Test
    void malformedJsonNeverPassesSilently() throws Exception {
        // 200 OK but the content is not valid JSON despite a structured request.
        try (StubHttpServer stub = StubHttpServer.start(200, plainReply("not json at all"))) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.structured(
                    List.of(ChatMessage.user("x")), OK_SCHEMA)))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> !((LlmException) e).isRetryable(), "permanent")
                    .hasMessageContaining("not valid JSON");
        }
    }

    // --- error / retryable mapping -------------------------------------------------------------

    @Test
    void http401IsPermanent() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(401, "{\"error\":\"bad key\"}")) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            assertThatThrownBy(provider::probe)
                    .isInstanceOf(LlmException.class)
                    .matches(e -> !((LlmException) e).isRetryable(), "permanent")
                    .hasMessageContaining("401");
        }
    }

    @Test
    void http429IsRetryable() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(429, "{\"error\":\"slow down\"}")) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).isRetryable(), "retryable")
                    .hasMessageContaining("429");
        }
    }

    @Test
    void contentFilterFinishReasonBecomesPermanent() throws Exception {
        String filtered = """
                {"id":"c","model":"gpt-5.5","choices":[
                  {"index":0,"finish_reason":"content_filter",
                   "message":{"role":"assistant","content":""}}]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, filtered)) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> !((LlmException) e).isRetryable())
                    .hasMessageContaining("content_filter");
        }
    }

    @Test
    void refusalMessageBecomesPermanent() throws Exception {
        String refusal = """
                {"id":"c","model":"gpt-5.5","choices":[
                  {"index":0,"finish_reason":"stop",
                   "message":{"role":"assistant","content":null,"refusal":"I can't help with that."}}]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, refusal)) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> !((LlmException) e).isRetryable())
                    .hasMessageContaining("refusal");
        }
    }

    // --- base-URL resolution -------------------------------------------------------------------

    @Test
    void compatWithoutBaseUrlFailsFast() {
        ProviderAuth noBase = new ProviderAuth(OpenAiCompatLlmProvider.COMPAT_KEY, "sk", null, "m");

        assertThatThrownBy(() -> compat(noBase))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("requires an explicit base URL");
    }

    @Test
    void openaiDefaultsBaseUrlAndModel() {
        // 'openai' key with no base url / model resolves to the OpenAI defaults (construction only).
        ProviderAuth bare = new ProviderAuth(OpenAiCompatLlmProvider.PROVIDER_KEY, "sk", null, null);
        OpenAiCompatLlmProvider provider =
                new OpenAiCompatLlmProvider(OpenAiCompatLlmProvider.PROVIDER_KEY, bare);

        assertThat(provider.id()).isEqualTo("openai");
        assertThat(provider.model()).isEqualTo(OpenAiCompatLlmProvider.DEFAULT_MODEL);
    }

    @Test
    void normalizesTrailingSlashInBaseUrl() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            ProviderAuth withSlash =
                    new ProviderAuth(OpenAiCompatLlmProvider.COMPAT_KEY, "sk", stub.baseUrl() + "/", null);
            OpenAiCompatLlmProvider provider = compat(withSlash);

            provider.chat(ChatRequest.text(ChatMessage.ofUser("x")));

            // No double slash before chat/completions.
            assertThat(stub.lastRequestPath()).isEqualTo("/chat/completions");
        }
    }

    @Test
    void usesConfiguredModelOverDefault() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            ProviderAuth withModel = new ProviderAuth(
                    OpenAiCompatLlmProvider.COMPAT_KEY, "sk", stub.baseUrl(), "deepseek-chat");
            OpenAiCompatLlmProvider provider = compat(withModel);

            assertThat(provider.model()).isEqualTo("deepseek-chat");
            provider.chat(ChatRequest.text(ChatMessage.ofUser("x")));
            assertThat(stub.lastRequestBody()).contains("\"model\":\"deepseek-chat\"");
        }
    }

    @Test
    void probeSucceedsAgainstHealthyEndpoint() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            OpenAiCompatLlmProvider provider = compat(compatAuth(stub.baseUrl()));

            provider.probe(); // must not throw

            // The probe uses a structured request, so response_format is present.
            assertThat(stub.lastRequestBody()).contains("response_format");
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** A minimal Chat Completions 200 body carrying a free-form {@code content} string. */
    private static String plainReply(String content) {
        return "{\"id\":\"c\",\"model\":\"gpt-5.5\",\"choices\":[{\"index\":0,"
                + "\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                + content + "\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";
    }
}
