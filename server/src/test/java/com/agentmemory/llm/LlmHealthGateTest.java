package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The health gate encodes DD-005's asymmetry: a required-LLM probe failure is fatal (invariant #13)
 * while an embeddings probe failure is not. Both branches are exercised here with the deterministic
 * double — no network.
 */
class LlmHealthGateTest {

    @Test
    void passesWhenBothProbesSucceed() {
        TestDoubleProvider healthy = TestDoubleProvider.create();

        assertThatCode(() -> new LlmHealthGate(healthy, healthy).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void failsFastWhenRequiredLlmProbeFails() {
        TestDoubleProvider badLlm = TestDoubleProvider.builder().failProbe(true).build();
        TestDoubleProvider goodEmbedder = TestDoubleProvider.create();

        assertThatThrownBy(() -> new LlmHealthGate(badLlm, goodEmbedder).verify())
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Required LLM provider")
                .hasMessageContaining("cannot start");
    }

    @Test
    void embeddingsProbeFailureIsNonFatal() {
        TestDoubleProvider goodLlm = TestDoubleProvider.create();
        TestDoubleProvider badEmbedder = TestDoubleProvider.builder().failProbe(true).build();

        // Embeddings are an optional axis (DD-005): a failure must NOT abort, only warn.
        assertThatCode(() -> new LlmHealthGate(goodLlm, badEmbedder).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void nullEmbedderIsTolerated() {
        TestDoubleProvider goodLlm = TestDoubleProvider.create();

        assertThatCode(() -> new LlmHealthGate(goodLlm, null).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void nullLlmProviderIsRejected() {
        assertThatThrownBy(() -> new LlmHealthGate(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void embeddingDimContractIsTheDocumentedDefault() {
        // DoD #3: the embedding dim contract issue #4 sizes its pgvector column to is documented and
        // pinned here as 1024 (Voyage voyage-3).
        assertThat(VoyageEmbedder.DEFAULT_DIMENSIONS).isEqualTo(1024);
    }
}
