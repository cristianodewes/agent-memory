package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.AgentMemoryProperties;
import com.agentmemory.config.AutoScope;
import com.agentmemory.config.ProviderAuth;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for the synthesis-route resolution (issue #156), {@link LlmModule#resolveSynthesisProvider}:
 * unset ⇒ the same primary instance; configured ⇒ the synthesis model, falling back to the primary on
 * failure; a broken block ⇒ the primary (best-effort, never throws). Network-free, like
 * {@link RecallLlmProviderResolutionTest}.
 */
class SynthesisLlmProviderResolutionTest {

    private final TestDoubleProvider primary = TestDoubleProvider.builder()
            .model("chat-model")
            .chatResponder(req -> "from primary")
            .build();

    private static AgentMemoryConfig config(Path dataDir) {
        AgentMemoryProperties props = new AgentMemoryProperties(
                new AgentMemoryProperties.Server("127.0.0.1", 0, "/", ""),
                new AgentMemoryProperties.Data(dataDir.toString()),
                new AgentMemoryProperties.Db("jdbc:postgresql://localhost/db", "u", ""),
                new AgentMemoryProperties.Llm(new ProviderAuth("test", "sk-chat", null, "chat-model")),
                new AgentMemoryProperties.Embeddings(ProviderAuth.NONE),
                new AgentMemoryProperties.Auth(false, "", List.of(), ""),
                new AgentMemoryProperties.Sanitization(65536, List.of()),
                new AgentMemoryProperties.Ingest(1024, 0),
                new AgentMemoryProperties.Decay(0.02, 1.0, 0.01, 1.0, 0.05, 30, 7),
                new AgentMemoryProperties.Scope(AutoScope.SINGLE_SLOT));
        return AgentMemoryConfig.resolve(props);
    }

    @Test
    void unsetSynthesisAuthReusesThePrimaryInstance(@TempDir Path tmp) {
        ProviderFactory factory = new ProviderFactory();
        AgentMemoryConfig config = config(tmp);

        assertThat(LlmModule.resolveSynthesisProvider(factory, config, primary, null)).isSameAs(primary);
        assertThat(LlmModule.resolveSynthesisProvider(factory, config, primary, ProviderAuth.NONE))
                .isSameAs(primary);
    }

    @Test
    void modelOnlyAnswersOnTheSynthesisModel(@TempDir Path tmp) {
        ProviderFactory factory = new ProviderFactory();
        factory.registerLlm("test", auth -> TestDoubleProvider.builder()
                .model(auth.model())
                .chatResponder(req -> "from " + auth.model())
                .build());

        LlmProvider synthesis = LlmModule.resolveSynthesisProvider(
                factory, config(tmp), primary, new ProviderAuth(null, null, null, "synthesis-model"));

        assertThat(synthesis.model()).isEqualTo("synthesis-model");
        assertThat(synthesis.chat(ChatRequest.text(List.of(ChatMessage.user("x")))).text())
                .isEqualTo("from synthesis-model");
        assertThat(primary.chatCalls()).isEmpty();
    }

    @Test
    void aFailingSynthesisModelFallsBackToThePrimary(@TempDir Path tmp) {
        ProviderFactory factory = new ProviderFactory();
        factory.registerLlm("test", auth -> TestDoubleProvider.builder()
                .model(auth.model())
                .chatResponder(req -> {
                    throw LlmException.permanent("HTTP 404 model not found", null);
                })
                .build());

        LlmProvider synthesis = LlmModule.resolveSynthesisProvider(
                factory, config(tmp), primary, new ProviderAuth(null, null, null, "no-such-model"));

        assertThat(synthesis.chat(ChatRequest.text(List.of(ChatMessage.user("x")))).text())
                .isEqualTo("from primary");
        assertThat(primary.chatCalls()).hasSize(1);
    }

    @Test
    void brokenSynthesisAuthFallsBackToPrimaryInsteadOfThrowing(@TempDir Path tmp) {
        ProviderAuth bad = new ProviderAuth("does-not-exist", "k", null, "m");

        assertThat(LlmModule.resolveSynthesisProvider(new ProviderFactory(), config(tmp), primary, bad))
                .isSameAs(primary);
    }
}
