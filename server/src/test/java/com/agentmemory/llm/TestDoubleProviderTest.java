package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The deterministic test double is the backbone of every downstream issue's LLM test, so its
 * guarantees — reproducible vectors, schema-shaped structured replies, an introspectable probe
 * failure — are pinned here.
 */
class TestDoubleProviderTest {

    @Test
    void chatEchoesAndReportsProviderModel() {
        TestDoubleProvider provider = TestDoubleProvider.create();

        ChatResponse response = provider.chat(ChatRequest.text(ChatMessage.ofUser("hello")));

        assertThat(response.provider()).isEqualTo("test");
        assertThat(response.model()).isEqualTo("test-model");
        assertThat(response.text()).contains("hello");
        assertThat(provider.chatCalls()).hasSize(1);
    }

    @Test
    void structuredRequestYieldsParseableJson() {
        TestDoubleProvider provider = TestDoubleProvider.create();
        JsonSchema schema = new JsonSchema("echo",
                "{\"type\":\"object\",\"properties\":{\"echo\":{\"type\":\"string\"}}}");

        ChatResponse response = provider.chat(
                ChatRequest.structured(ChatMessage.ofUser("payload"), schema));

        // Default structured reply is {"echo": "..."} — well-formed JSON, as invariant #7 demands.
        assertThat(response.text()).startsWith("{").contains("\"echo\"").endsWith("}");
    }

    @Test
    void chatResponderScriptsExactReplies() {
        TestDoubleProvider provider = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"answer\":42}")
                .build();

        ChatResponse response = provider.chat(ChatRequest.text(ChatMessage.ofUser("q")));

        assertThat(response.text()).isEqualTo("{\"answer\":42}");
    }

    @Test
    void embedIsDeterministicUnitVectorOfConfiguredDim() {
        TestDoubleProvider provider = TestDoubleProvider.create();

        EmbeddingResult a = provider.embed("same text");
        EmbeddingResult b = provider.embed("same text");
        EmbeddingResult c = provider.embed("different text");

        assertThat(a.dim()).isEqualTo(TestDoubleProvider.DEFAULT_DIMENSIONS);
        assertThat(a.provider()).isEqualTo("test");
        assertThat(a.vector()).containsExactly(b.vector());           // reproducible
        assertThat(a.vector()).isNotEqualTo(c.vector());              // input-sensitive
        double norm = 0.0;
        for (float v : a.vector()) {
            norm += (double) v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5)); // L2-normalized
    }

    @Test
    void embedAllPreservesOrder() {
        TestDoubleProvider provider = TestDoubleProvider.create();

        List<EmbeddingResult> results = provider.embedAll(List.of("one", "two", "three"));

        assertThat(results).hasSize(3);
        assertThat(results.get(0).vector()).containsExactly(provider.embed("one").vector());
        assertThat(results.get(2).vector()).containsExactly(provider.embed("three").vector());
    }

    @Test
    void failProbeThrows() {
        TestDoubleProvider provider = TestDoubleProvider.builder().failProbe(true).build();

        assertThatThrownBy(provider::probe).isInstanceOf(LlmException.class);
    }

    @Test
    void healthyProbeDoesNotThrow() {
        TestDoubleProvider.create().probe();
        // no exception = pass
    }

    @Test
    void customDimensionsHonored() {
        TestDoubleProvider provider = TestDoubleProvider.builder().dimensions(1024).build();

        assertThat(provider.dimensions()).isEqualTo(1024);
        assertThat(provider.embed("x").dim()).isEqualTo(1024);
    }
}
