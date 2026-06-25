package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.config.ProviderAuth;
import org.junit.jupiter.api.Test;

/**
 * The factory is the data-driven seam that lets issue #40 add providers without touching consumers,
 * so its selection rules — key normalization, the shared test double across both axes, real provider
 * construction from typed auth, and clear failures on missing/unknown keys — are covered here.
 */
class ProviderFactoryTest {

    private final ProviderFactory factory = new ProviderFactory();

    @Test
    void selectsAnthropicForChatAxis() {
        ProviderAuth auth = new ProviderAuth("anthropic", "sk-test", null, null);

        LlmProvider provider = factory.createLlmProvider(auth);

        assertThat(provider).isInstanceOf(AnthropicLlmProvider.class);
        assertThat(provider.id()).isEqualTo("anthropic");
        assertThat(provider.model()).isEqualTo(AnthropicLlmProvider.DEFAULT_MODEL);
    }

    @Test
    void providerKeyIsCaseInsensitive() {
        ProviderAuth auth = new ProviderAuth("Anthropic", "sk-test", null, "claude-opus-4-8");

        LlmProvider provider = factory.createLlmProvider(auth);

        assertThat(provider.id()).isEqualTo("anthropic");
        assertThat(provider.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void selectsVoyageForEmbeddingsAxis() {
        ProviderAuth auth = new ProviderAuth("voyage", "pa-test", null, null);

        Embedder embedder = factory.createEmbedder(auth);

        assertThat(embedder).isInstanceOf(VoyageEmbedder.class);
        assertThat(embedder.id()).isEqualTo("voyage");
        assertThat(embedder.dimensions()).isEqualTo(VoyageEmbedder.DEFAULT_DIMENSIONS);
    }

    @Test
    void selectsGeminiForChatAxis() {
        // Native Gemini chat provider (issue #40). Construction only — round-trip in GeminiLlmProviderTest.
        ProviderAuth auth = new ProviderAuth("gemini", "k-x", null, null);

        LlmProvider provider = factory.createLlmProvider(auth);

        assertThat(provider).isInstanceOf(GeminiLlmProvider.class);
        assertThat(provider.id()).isEqualTo("gemini");
        assertThat(provider.model()).isEqualTo(GeminiLlmProvider.DEFAULT_MODEL);
    }

    @Test
    void selectsOpenAiForEmbeddingsAxis() {
        // 'openai' on the embeddings axis resolves the OpenAI embedder (issue #40), distinct from the
        // 'openai' chat provider on the chat axis.
        ProviderAuth auth = new ProviderAuth("openai", "sk-x", null, null);

        Embedder embedder = factory.createEmbedder(auth);

        assertThat(embedder).isInstanceOf(OpenAiEmbedder.class);
        assertThat(embedder.id()).isEqualTo("openai");
        assertThat(embedder.dimensions()).isEqualTo(OpenAiEmbedder.DIMENSIONS);
    }

    @Test
    void selectsGoogleForEmbeddingsAxis() {
        ProviderAuth auth = new ProviderAuth("google", "k-x", null, null);

        Embedder embedder = factory.createEmbedder(auth);

        assertThat(embedder).isInstanceOf(GoogleEmbedder.class);
        assertThat(embedder.id()).isEqualTo("google");
        assertThat(embedder.dimensions()).isEqualTo(GoogleEmbedder.DIMENSIONS);
    }

    @Test
    void testKeyReturnsSharedDoubleForBothAxes() {
        TestDoubleProvider shared = TestDoubleProvider.create();
        ProviderFactory f = new ProviderFactory(shared);
        ProviderAuth auth = new ProviderAuth("test", null, null, null);

        LlmProvider llm = f.createLlmProvider(auth);
        Embedder embedder = f.createEmbedder(auth);

        // Same object backs both axes, so a test can introspect calls across them.
        assertThat(llm).isSameAs(shared);
        assertThat(embedder).isSameAs(shared);
    }

    @Test
    void selectsOpenAiProviderWithDefaultEndpoint() {
        // 'openai' resolves a real provider that defaults to the OpenAI endpoint/model (issue #46).
        // Construction only — no network call; the round-trip is covered in OpenAiCompatLlmProviderTest.
        ProviderAuth auth = new ProviderAuth("openai", "sk-x", null, null);

        LlmProvider provider = factory.createLlmProvider(auth);

        assertThat(provider).isInstanceOf(OpenAiCompatLlmProvider.class);
        assertThat(provider.id()).isEqualTo("openai");
        assertThat(provider.model()).isEqualTo(OpenAiCompatLlmProvider.DEFAULT_MODEL);
    }

    @Test
    void selectsOpenAiCompatAndFailsFastWithoutBaseUrl() {
        // 'openai-compat' is registered and requires an explicit base_url — fail fast at construction.
        assertThat(factory.llmProviderKeys()).contains("openai-compat");

        ProviderAuth noBase = new ProviderAuth("openai-compat", "sk-x", null, null);
        assertThatThrownBy(() -> factory.createLlmProvider(noBase))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("requires an explicit base URL");
    }

    @Test
    void unconfiguredProviderFailsWithActionableMessage() {
        assertThatThrownBy(() -> factory.createLlmProvider(ProviderAuth.NONE))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("agent-memory.llm.auth.provider");
    }

    @Test
    void unknownProviderKeyFailsAndListsKnownKeys() {
        ProviderAuth auth = new ProviderAuth("acme-llm", "k", null, null);

        assertThatThrownBy(() -> factory.createLlmProvider(auth))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("Unknown LLM provider 'acme-llm'")
                .hasMessageContaining("anthropic");
    }

    @Test
    void missingApiKeyForRealProviderFailsFast() {
        // Anthropic requires a key; resolving auth without one must fail at construction (invariant #14).
        ProviderAuth auth = new ProviderAuth("anthropic", null, null, null);

        assertThatThrownBy(() -> factory.createLlmProvider(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No API key configured");
    }

    @Test
    void newProvidersRegisterWithoutTouchingConsumers() {
        // Demonstrates the extensibility contract: a brand-new key is usable after one registration.
        ProviderFactory f = new ProviderFactory();
        f.registerLlm("acme", auth -> TestDoubleProvider.builder().model("acme-1").build());

        LlmProvider provider = f.createLlmProvider(new ProviderAuth("acme", null, null, null));

        assertThat(provider.model()).isEqualTo("acme-1");
        assertThat(f.llmProviderKeys()).contains("acme");
    }
}
