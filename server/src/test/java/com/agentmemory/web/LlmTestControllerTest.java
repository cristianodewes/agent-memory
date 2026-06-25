package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.Embedder;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.TestDoubleProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit-level coverage for the {@code /llm-test} manual-verification endpoint, matching the plain
 * (no Spring MVC slice) style of {@link HealthControllerTest}. The endpoint must report ok/provider/
 * model for the chat axis, a real round-trip reply, and either a configured-and-ok or
 * not-configured embeddings axis — all without a network (the deterministic double backs both).
 */
class LlmTestControllerTest {

    /** Minimal {@link ObjectProvider} that always yields the same (possibly null) embedder. */
    private static ObjectProvider<Embedder> provider(Embedder embedder) {
        return new ObjectProvider<>() {
            @Override
            public Embedder getObject(Object... args) {
                return embedder;
            }

            @Override
            public Embedder getObject() {
                return embedder;
            }

            @Override
            public Embedder getIfAvailable() {
                return embedder;
            }

            @Override
            public Embedder getIfUnique() {
                return embedder;
            }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsOkForBothAxesWithTestDouble() {
        TestDoubleProvider both = TestDoubleProvider.create();
        LlmTestController controller = new LlmTestController(both, provider(both));

        Map<String, Object> result = controller.llmTest();

        Map<String, Object> llm = (Map<String, Object>) result.get("llm");
        assertThat(llm).containsEntry("provider", "test").containsEntry("ok", true);
        assertThat(llm.get("reply")).asString().contains("echo"); // structured reply is JSON

        Map<String, Object> embeddings = (Map<String, Object>) result.get("embeddings");
        assertThat(embeddings).containsEntry("configured", true).containsEntry("ok", true);
        assertThat(embeddings).containsEntry("dim", TestDoubleProvider.DEFAULT_DIMENSIONS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsEmbeddingsNotConfiguredWhenAbsent() {
        LlmProvider llm = TestDoubleProvider.create();
        LlmTestController controller = new LlmTestController(llm, provider(null));

        Map<String, Object> result = controller.llmTest();

        Map<String, Object> embeddings = (Map<String, Object>) result.get("embeddings");
        assertThat(embeddings).containsEntry("configured", false).containsEntry("ok", false);
        assertThat(embeddings.get("note")).asString().contains("FTS + graph");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsErrorWhenChatProviderFails() {
        // A double whose chat path throws (stubbed reply that fails JSON would still parse, so use a
        // provider that always throws via a failing responder).
        LlmProvider failing = new LlmProvider() {
            @Override
            public com.agentmemory.llm.ChatResponse chat(com.agentmemory.llm.ChatRequest request) {
                throw com.agentmemory.llm.LlmException.retryable("boom", null);
            }

            @Override
            public String id() {
                return "test";
            }

            @Override
            public String model() {
                return "test-model";
            }

            @Override
            public void probe() {
                // not used by the endpoint
            }
        };
        LlmTestController controller = new LlmTestController(failing, provider(null));

        Map<String, Object> result = controller.llmTest();

        Map<String, Object> llm = (Map<String, Object>) result.get("llm");
        assertThat(llm).containsEntry("ok", false).containsEntry("retryable", true);
        assertThat(llm.get("error")).asString().contains("boom");
    }
}
