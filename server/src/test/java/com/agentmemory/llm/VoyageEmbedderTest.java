package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.config.ProviderAuth;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real Voyage embedder against a loopback stub (no external network): the request it
 * builds, denormalized {@code {provider, model, dim}} (invariant #8), the dimension-contract guard
 * (issue #4), and index-based ordering of batch results.
 */
class VoyageEmbedderTest {

    /** Build an embedder configured for 3-dim vectors so the stub bodies stay tiny. */
    private static VoyageEmbedder embedder(String baseUrl) {
        ProviderAuth auth = new ProviderAuth("voyage", "pa-test", baseUrl, "voyage-3");
        return new VoyageEmbedder(auth, 3, new HttpJsonClient(Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    @Test
    void embedsAndDenormalizesProviderModelDim() throws Exception {
        String response = """
                {"model":"voyage-3","data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, response)) {
            EmbeddingResult result = embedder(stub.baseUrl()).embed("hello");

            assertThat(result.provider()).isEqualTo("voyage");
            assertThat(result.model()).isEqualTo("voyage-3");
            assertThat(result.dim()).isEqualTo(3);
            assertThat(result.vector()).containsExactly(0.1f, 0.2f, 0.3f);

            // Request shape: model + input array.
            assertThat(stub.lastRequestBody()).contains("\"model\":\"voyage-3\"").contains("\"input\"");
        }
    }

    @Test
    void dimensionMismatchFailsRatherThanStoring() throws Exception {
        // Configured for 3 dims but the model returns 4 — invariant #8 guard must reject it.
        String response = """
                {"model":"voyage-3","data":[{"index":0,"embedding":[0.1,0.2,0.3,0.4]}]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, response)) {
            assertThatThrownBy(() -> embedder(stub.baseUrl()).embed("hello"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("4-dim")
                    .hasMessageContaining("configured for 3");
        }
    }

    @Test
    void batchResultsOrderedByIndex() throws Exception {
        // Items deliberately out of order; the embedder must reorder by 'index'.
        String response = """
                {"model":"voyage-3","data":[
                  {"index":1,"embedding":[1.0,1.0,1.0]},
                  {"index":0,"embedding":[0.0,0.0,0.0]}
                ]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, response)) {
            List<EmbeddingResult> results = embedder(stub.baseUrl()).embedAll(List.of("a", "b"));

            assertThat(results).hasSize(2);
            assertThat(results.get(0).vector()).containsExactly(0.0f, 0.0f, 0.0f); // index 0
            assertThat(results.get(1).vector()).containsExactly(1.0f, 1.0f, 1.0f); // index 1
        }
    }

    @Test
    void serverErrorIsRetryable() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(503, "{\"error\":\"unavailable\"}")) {
            assertThatThrownBy(() -> embedder(stub.baseUrl()).embed("x"))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).isRetryable(), "retryable");
        }
    }

    @Test
    void defaultDimensionsContractIs1024() {
        // The dim contract issue #4 sizes its pgvector column to.
        assertThat(VoyageEmbedder.DEFAULT_DIMENSIONS).isEqualTo(1024);
    }

    @Test
    void missingApiKeyFailsFast() {
        ProviderAuth auth = new ProviderAuth("voyage", null, "http://localhost:1", null);

        assertThatThrownBy(() -> new VoyageEmbedder(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No API key configured");
    }
}
