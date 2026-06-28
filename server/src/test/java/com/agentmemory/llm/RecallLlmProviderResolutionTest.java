package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.AgentMemoryProperties;
import com.agentmemory.config.AutoScope;
import com.agentmemory.config.ProviderAuth;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for the recall-provider resolution (issue #146, Fase 5): the three branches of
 * {@link LlmModule#resolveRecallProvider} plus its best-effort fallback. Network-free — a
 * {@link ProviderFactory} with scripted builders captures the auth it is asked to build from, so the
 * branch behaviour is asserted directly without booting a context.
 *
 * <ul>
 *   <li>unset recall auth ⇒ the <em>same</em> primary instance (no second provider);</li>
 *   <li>only a model ⇒ the chat auth with the model swapped (same provider/key/OAuth);</li>
 *   <li>a provider ⇒ an independent provider built straight from the recall auth;</li>
 *   <li>a broken recall auth ⇒ the primary (recall is best-effort and never throws here).</li>
 * </ul>
 */
class RecallLlmProviderResolutionTest {

    private final LlmProvider primary = TestDoubleProvider.builder().model("chat-model").build();

    private static AgentMemoryConfig config(Path dataDir, String chatProvider, String chatModel) {
        AgentMemoryProperties props = new AgentMemoryProperties(
                new AgentMemoryProperties.Server("127.0.0.1", 0, "/", ""),
                new AgentMemoryProperties.Data(dataDir.toString()),
                new AgentMemoryProperties.Db("jdbc:postgresql://localhost/db", "u", ""),
                new AgentMemoryProperties.Llm(new ProviderAuth(chatProvider, "sk-chat", null, chatModel)),
                new AgentMemoryProperties.Embeddings(ProviderAuth.NONE),
                new AgentMemoryProperties.Auth(false, "", List.of(), ""),
                new AgentMemoryProperties.Sanitization(65536, List.of()),
                new AgentMemoryProperties.Ingest(1024, 0),
                new AgentMemoryProperties.Decay(0.02, 1.0, 0.01, 1.0, 0.05, 30, 7),
                new AgentMemoryProperties.Scope(AutoScope.SINGLE_SLOT));
        return AgentMemoryConfig.resolve(props);
    }

    @Test
    void unsetRecallAuthReusesThePrimaryInstance(@TempDir Path tmp) {
        ProviderFactory factory = new ProviderFactory();
        AgentMemoryConfig config = config(tmp, "test", "chat-model");

        // null, NONE, and "api-key/base-url but no provider or model" all mean "not configured for
        // recall" -> the SAME primary object, so there is no second client to build or probe.
        assertThat(LlmModule.resolveRecallProvider(factory, config, primary, null)).isSameAs(primary);
        assertThat(LlmModule.resolveRecallProvider(factory, config, primary, ProviderAuth.NONE))
                .isSameAs(primary);
        assertThat(LlmModule.resolveRecallProvider(
                factory, config, primary, new ProviderAuth(null, "k", "http://x", null)))
                .isSameAs(primary);
    }

    @Test
    void modelOnlyReusesChatAuthWithModelSwapped(@TempDir Path tmp) {
        AtomicReference<ProviderAuth> built = new AtomicReference<>();
        ProviderFactory factory = new ProviderFactory();
        factory.registerLlm("test", auth -> {
            built.set(auth);
            return TestDoubleProvider.builder().model(auth.model()).build();
        });
        AgentMemoryConfig config = config(tmp, "test", "chat-model");

        // provider empty, only a model set -> reuse the chat auth (provider/key/OAuth), swap the model.
        ProviderAuth recallAuth = new ProviderAuth(null, null, null, "recall-mini");
        LlmProvider recall = LlmModule.resolveRecallProvider(factory, config, primary, recallAuth);

        assertThat(recall).isNotSameAs(primary);
        assertThat(recall.model()).isEqualTo("recall-mini");
        assertThat(built.get().providerKey()).isEqualTo("test");
        assertThat(built.get().apiKey()).isEqualTo("sk-chat");
        assertThat(built.get().model()).isEqualTo("recall-mini");
    }

    @Test
    void providerSetBuildsAnIndependentProvider(@TempDir Path tmp) {
        AtomicReference<ProviderAuth> built = new AtomicReference<>();
        ProviderFactory factory = new ProviderFactory();
        factory.registerLlm("acme", auth -> {
            built.set(auth);
            return TestDoubleProvider.builder().model(auth.model()).build();
        });
        AgentMemoryConfig config = config(tmp, "test", "chat-model");

        // provider set -> an independent provider/key/base-url/model, NOT the chat auth.
        ProviderAuth recallAuth = new ProviderAuth("acme", "sk-recall", "https://acme/v1", "mini-1");
        LlmProvider recall = LlmModule.resolveRecallProvider(factory, config, primary, recallAuth);

        assertThat(recall).isNotSameAs(primary);
        assertThat(recall.model()).isEqualTo("mini-1");
        assertThat(built.get()).isSameAs(recallAuth);
    }

    @Test
    void brokenRecallAuthFallsBackToPrimaryInsteadOfThrowing(@TempDir Path tmp) {
        // An unknown provider key the factory cannot build must not bubble up: recall is best-effort, so
        // resolution swallows the error and returns the primary (recall degrades to RRF, boot is safe).
        ProviderFactory factory = new ProviderFactory();
        AgentMemoryConfig config = config(tmp, "test", "chat-model");

        ProviderAuth bad = new ProviderAuth("does-not-exist", "k", null, "m");
        assertThat(LlmModule.resolveRecallProvider(factory, config, primary, bad)).isSameAs(primary);
    }
}
