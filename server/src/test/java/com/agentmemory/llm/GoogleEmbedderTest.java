package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.config.ProviderAuth;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real Google (Gemini) embedder against a loopback stub (no external network): the
 * {@code embedContent} request shape (content.parts + pinned {@code output_dimensionality}), the
 * {@code embedding.values} parse, denormalized {@code {provider, model, dim}} (invariant #8), and the
 * dimension-contract guard (issue #4).
 */
class GoogleEmbedderTest {

    /** Build an embedder configured for 3-dim vectors so the stub bodies stay tiny. */
    private static GoogleEmbedder embedder(String baseUrl) {
        ProviderAuth auth = new ProviderAuth("google", "k-test", baseUrl, "gemini-embedding-001");
        return new GoogleEmbedder(auth, 3, new HttpJsonClient(Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    @Test
    void embedsAndDenormalizesProviderModelDim() throws Exception {
        String response = """
                {"embedding":{"values":[0.1,0.2,0.3]}}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, response)) {
            EmbeddingResult result = embedder(stub.baseUrl()).embed("hello");

            assertThat(result.provider()).isEqualTo("google");
            assertThat(result.model()).isEqualTo("gemini-embedding-001");
            assertThat(result.dim()).isEqualTo(3);
            assertThat(result.vector()).containsExactly(0.1f, 0.2f, 0.3f);

            // Request shape: content.parts + pinned output width; key in x-goog-api-key header.
            String body = stub.lastRequestBody();
            assertThat(body).contains("\"content\"").contains("\"parts\"").contains("hello");
            assertThat(body).contains("\"output_dimensionality\":3");
            assertThat(stub.lastRequestPath())
                    .isEqualTo("/v1beta/models/gemini-embedding-001:embedContent");
            assertThat(stub.lastRequestHeader("x-goog-api-key")).isEqualTo("k-test");
        }
    }

    @Test
    void dimensionMismatchFailsRatherThanStoring() throws Exception {
        String response = """
                {"embedding":{"values":[0.1,0.2,0.3,0.4]}}
                """;
        try (StubHttpServer stub = StubHttpServer.start(200, response)) {
            assertThatThrownBy(() -> embedder(stub.baseUrl()).embed("hello"))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("4-dim")
                    .hasMessageContaining("configured for 3");
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
        assertThat(GoogleEmbedder.DIMENSIONS).isEqualTo(1024);
    }

    @Test
    void missingApiKeyFailsFast() {
        ProviderAuth auth = new ProviderAuth("google", null, "http://localhost:1", null);
        assertThatThrownBy(() -> new GoogleEmbedder(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No API key configured");
    }
}
