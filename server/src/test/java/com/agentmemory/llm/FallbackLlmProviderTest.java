package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link FallbackLlmProvider} (issue #156): the synthesis route prefers its own model
 * and, on any provider failure, repeats the same request on the primary so session pages and handoffs
 * keep being written. Network-free — {@link TestDoubleProvider}s script success or failure and record
 * every call.
 */
class FallbackLlmProviderTest {

    private static final ChatRequest REQUEST = ChatRequest.text(List.of(ChatMessage.user("summarize")));

    private static TestDoubleProvider answering(String model, String text) {
        return TestDoubleProvider.builder().model(model).chatResponder(req -> text).build();
    }

    private static TestDoubleProvider failing(String model, String message) {
        return TestDoubleProvider.builder()
                .model(model)
                .chatResponder(req -> {
                    throw LlmException.permanent(message, null);
                })
                .build();
    }

    @Test
    void preferredAnswerIsReturnedWithoutTouchingTheFallback() {
        TestDoubleProvider preferred = answering("synthesis-model", "from preferred");
        TestDoubleProvider fallback = answering("chat-model", "from fallback");

        ChatResponse response = new FallbackLlmProvider(preferred, fallback, "Synthesis").chat(REQUEST);

        assertThat(response.text()).isEqualTo("from preferred");
        assertThat(preferred.chatCalls()).containsExactly(REQUEST);
        assertThat(fallback.chatCalls()).isEmpty();
    }

    @Test
    void preferredFailureRepeatsTheSameRequestOnTheFallback() {
        TestDoubleProvider preferred = failing("synthesis-model", "HTTP 404 model not found");
        TestDoubleProvider fallback = answering("chat-model", "from fallback");

        ChatResponse response = new FallbackLlmProvider(preferred, fallback, "Synthesis").chat(REQUEST);

        assertThat(response.text()).isEqualTo("from fallback");
        assertThat(fallback.chatCalls()).containsExactly(REQUEST);
    }

    @Test
    void whenBothFailTheFallbackErrorSurfaces() {
        // The primary's error is the one the dispatchers' rate-limit gate and retry act on.
        TestDoubleProvider preferred = failing("synthesis-model", "synthesis quota exhausted");
        TestDoubleProvider fallback = failing("chat-model", "primary credits depleted");

        assertThatThrownBy(() -> new FallbackLlmProvider(preferred, fallback, "Synthesis").chat(REQUEST))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("primary credits depleted");
    }

    @Test
    void reportsThePreferredIdentity() {
        FallbackLlmProvider provider = new FallbackLlmProvider(
                answering("synthesis-model", "x"), answering("chat-model", "y"), "Synthesis");

        assertThat(provider.id()).isEqualTo(TestDoubleProvider.PROVIDER_KEY);
        assertThat(provider.model()).isEqualTo("synthesis-model");
    }
}
