package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.config.ProviderAuth;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real Anthropic client against a loopback stub (no external network): the Messages
 * API request shape it builds (system hoisting, {@code output_config.format} for invariant #7), how
 * it parses the {@code content[]} reply and usage, and how it maps refusals and HTTP errors to
 * {@link LlmException}.
 */
class AnthropicLlmProviderTest {

    private static final String OK_RESPONSE = """
            {
              "id": "msg_1",
              "model": "claude-opus-4-8",
              "stop_reason": "end_turn",
              "content": [{"type": "text", "text": "{\\"ok\\":true}"}],
              "usage": {"input_tokens": 11, "output_tokens": 5}
            }
            """;

    private static ProviderAuth auth(String baseUrl) {
        return new ProviderAuth("anthropic", "sk-test", baseUrl, null);
    }

    @Test
    void sendsStructuredRequestAndParsesReply() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            AnthropicLlmProvider provider = new AnthropicLlmProvider(auth(stub.baseUrl()));

            JsonSchema schema = new JsonSchema("t",
                    "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                            + "\"required\":[\"ok\"],\"additionalProperties\":false}");
            ChatResponse response = provider.chat(ChatRequest.structured(
                    List.of(ChatMessage.system("be terse"), ChatMessage.user("ping")), schema));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");
            assertThat(response.provider()).isEqualTo("anthropic");
            assertThat(response.model()).isEqualTo("claude-opus-4-8");
            assertThat(response.inputTokens()).isEqualTo(11);
            assertThat(response.outputTokens()).isEqualTo(5);

            // Request shape: system hoisted out of messages[], output_config.format present (inv #7).
            String body = stub.lastRequestBody();
            assertThat(body).contains("\"system\":\"be terse\"");
            assertThat(body).contains("\"output_config\"").contains("\"json_schema\"");
            assertThat(body).contains("\"max_tokens\"");
            assertThat(body).doesNotContain("\"role\":\"system\""); // system is NOT a message turn
        }
    }

    @Test
    void freeFormRequestOmitsOutputConfig() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            AnthropicLlmProvider provider = new AnthropicLlmProvider(auth(stub.baseUrl()));

            provider.chat(ChatRequest.text(ChatMessage.ofUser("hi")));

            assertThat(stub.lastRequestBody()).doesNotContain("output_config");
        }
    }

    @Test
    void refusalBecomesPermanentException() throws Exception {
        String refusal = """
                {"id":"m","model":"claude-opus-4-8","stop_reason":"refusal","content":[]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, refusal)) {
            AnthropicLlmProvider provider = new AnthropicLlmProvider(auth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("refusal")
                    .matches(e -> !((LlmException) e).isRetryable());
        }
    }

    @Test
    void http401IsPermanent() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(401, "{\"error\":\"bad key\"}")) {
            AnthropicLlmProvider provider = new AnthropicLlmProvider(auth(stub.baseUrl()));

            assertThatThrownBy(provider::probe)
                    .isInstanceOf(LlmException.class)
                    .matches(e -> !((LlmException) e).isRetryable(), "permanent")
                    .hasMessageContaining("401");
        }
    }

    @Test
    void http429IsRetryable() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(429, "{\"error\":\"slow down\"}")) {
            AnthropicLlmProvider provider = new AnthropicLlmProvider(auth(stub.baseUrl()));

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).isRetryable(), "retryable")
                    .hasMessageContaining("429");
        }
    }

    @Test
    void probeSucceedsAgainstHealthyEndpoint() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            AnthropicLlmProvider provider = new AnthropicLlmProvider(auth(stub.baseUrl()));

            provider.probe(); // must not throw

            // The probe uses a structured request, so output_config is present.
            assertThat(stub.lastRequestBody()).contains("output_config");
        }
    }

    @Test
    void usesConfiguredModelOverDefault() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(200, OK_RESPONSE)) {
            ProviderAuth withModel = new ProviderAuth("anthropic", "sk", stub.baseUrl(), "claude-sonnet-4-6");
            AnthropicLlmProvider provider = new AnthropicLlmProvider(withModel);

            assertThat(provider.model()).isEqualTo("claude-sonnet-4-6");
            provider.chat(ChatRequest.text(ChatMessage.ofUser("x")));
            assertThat(stub.lastRequestBody()).contains("\"model\":\"claude-sonnet-4-6\"");
        }
    }
}
