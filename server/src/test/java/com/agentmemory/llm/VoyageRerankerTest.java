package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.config.ProviderAuth;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real Voyage cross-encoder reranker against a loopback stub (no external network): the
 * request it builds ({@code model}/{@code query}/{@code documents}/{@code top_k}), index-aligned
 * full-coverage scoring (every input document gets a calibrated score back), score clamping, and the
 * failure modes the {@code CrossEncoderReranker} degrades on (issue #130, Fase 2).
 */
class VoyageRerankerTest {

    private static VoyageReranker reranker(String baseUrl) {
        ProviderAuth auth = new ProviderAuth("voyage", "pa-test", baseUrl, "voyage-3");
        return new VoyageReranker(
                auth, "rerank-2-lite", new HttpJsonClient(Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    @Test
    void scoresEveryDocumentAlignedByIndex() throws Exception {
        // Data deliberately out of order; the reranker must align scores back to input order by 'index'.
        String response = """
                {"data":[
                  {"index":1,"relevance_score":0.9},
                  {"index":0,"relevance_score":0.2}
                ]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, response)) {
            double[] scores = reranker(stub.baseUrl())
                    .scoreDocuments("query", List.of("doc A", "doc B"), null);

            assertThat(scores).containsExactly(0.2, 0.9);

            // Request shape: model + query + documents + top_k = number of documents (full coverage).
            String body = stub.lastRequestBody();
            assertThat(body).contains("\"model\":\"rerank-2-lite\"");
            assertThat(body).contains("\"query\":\"query\"");
            assertThat(body).contains("\"documents\"").contains("doc A").contains("doc B");
            assertThat(body).contains("\"top_k\":2");
        }
    }

    @Test
    void clampsScoresIntoUnitRange() throws Exception {
        String response = """
                {"data":[{"index":0,"relevance_score":4.0},{"index":1,"relevance_score":-2.0}]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, response)) {
            double[] scores = reranker(stub.baseUrl())
                    .scoreDocuments("q", List.of("a", "b"), Duration.ofSeconds(3));

            assertThat(scores).containsExactly(1.0, 0.0); // 4.0 -> 1.0, -2.0 -> 0.0
        }
    }

    @Test
    void incompleteCoverageFails() throws Exception {
        // Two documents sent, only one scored back: the full-coverage contract is violated -> fail (the
        // CrossEncoderReranker then degrades to the LLM reranker).
        String response = """
                {"data":[{"index":0,"relevance_score":0.5}]}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, response)) {
            assertThatThrownBy(() -> reranker(stub.baseUrl())
                    .scoreDocuments("q", List.of("a", "b"), null))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("wrong size");
        }
    }

    @Test
    void serverErrorIsRetryable() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(503, "{\"error\":\"unavailable\"}")) {
            assertThatThrownBy(() -> reranker(stub.baseUrl()).scoreDocuments("q", List.of("a"), null))
                    .isInstanceOf(LlmException.class)
                    .matches(e -> ((LlmException) e).isRetryable(), "retryable");
        }
    }

    @Test
    void missingApiKeyFailsFast() {
        ProviderAuth auth = new ProviderAuth("voyage", null, "http://localhost:1", null);

        assertThatThrownBy(() -> new VoyageReranker(auth, "rerank-2-lite"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No API key configured");
    }

    @Test
    void modelDefaultsWhenBlank() {
        ProviderAuth auth = new ProviderAuth("voyage", "pa-test", "http://localhost:1", "voyage-3");
        assertThat(new VoyageReranker(auth, "  ").model()).isEqualTo(VoyageReranker.DEFAULT_MODEL);
    }

    @Test
    void blankDocumentIsRejected() {
        ProviderAuth auth = new ProviderAuth("voyage", "pa-test", "http://localhost:1", "voyage-3");
        VoyageReranker r = new VoyageReranker(auth, "rerank-2-lite");

        assertThatThrownBy(() -> r.scoreDocuments("q", List.of("ok", "  "), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
    }
}
