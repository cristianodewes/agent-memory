package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.AgentMemoryProperties;
import com.agentmemory.config.ProviderAuth;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The integration fail-fast test the issue requires: booting the {@link LlmModule} against a
 * <em>required</em> chat provider whose probe fails must abort context startup (invariant #13,
 * DD-005), with an {@link LlmException} in the cause chain. The companion test proves the happy path
 * wires {@link LlmProvider} / {@link Embedder} / {@link LlmHealthGate} beans and that an embeddings
 * probe failure is non-fatal.
 *
 * <p>No network: a {@link TestDoubleProvider} with {@code failProbe(true)} stands in for an
 * unreachable provider, and a custom {@link ProviderFactory} is registered (with bean-definition
 * overriding) so the module builds beans from it.
 */
class LlmModuleFailFastTest {

    private ApplicationContextRunner runner(AgentMemoryConfig config, ProviderFactory factory) {
        return new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withBean(AgentMemoryConfig.class, () -> config)
                .withBean(ProviderFactory.class, () -> factory)
                .withUserConfiguration(LlmModule.class);
    }

    private static AgentMemoryConfig configWithProviders(Path dataDir, String llm, String embeddings) {
        AgentMemoryProperties props = new AgentMemoryProperties(
                new AgentMemoryProperties.Server("127.0.0.1", 0, "/"),
                new AgentMemoryProperties.Data(dataDir.toString()),
                new AgentMemoryProperties.Db("jdbc:postgresql://localhost/db", "u", ""),
                new AgentMemoryProperties.Llm(new ProviderAuth(llm, null, null, null)),
                new AgentMemoryProperties.Embeddings(
                        embeddings == null ? ProviderAuth.NONE : new ProviderAuth(embeddings, null, null, null)),
                new AgentMemoryProperties.Auth(false, ""),
                new AgentMemoryProperties.Sanitization(65536, java.util.List.of()));
        return AgentMemoryConfig.resolve(props);
    }

    @Test
    void contextFailsToStartWhenRequiredLlmProbeFails(@TempDir Path tmp) {
        // A required chat provider whose probe throws -> startup must abort.
        ProviderFactory failing = new ProviderFactory(TestDoubleProvider.builder().failProbe(true).build());
        AgentMemoryConfig config = configWithProviders(tmp, "test", null);

        runner(config, failing).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(hasCauseOfType(ctx.getStartupFailure(), LlmException.class))
                    .as("startup failure should be caused by an LlmException")
                    .isTrue();
            assertThat(ctx.getStartupFailure()).hasMessageContaining("Required LLM provider");
        });
    }

    @Test
    void contextStartsAndWiresBeansWhenProviderHealthy(@TempDir Path tmp) {
        ProviderFactory healthy = new ProviderFactory(TestDoubleProvider.create());
        AgentMemoryConfig config = configWithProviders(tmp, "test", "test");

        runner(config, healthy).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // The 'llmProvider' and 'embedder' beans both exist by name. (The test double implements
            // both interfaces, so a by-type Embedder lookup is ambiguous without @Primary — which the
            // module sets; hence we assert the named beans here.)
            assertThat(ctx).hasBean("llmProvider");
            assertThat(ctx).hasBean("embedder");
            assertThat(ctx).hasSingleBean(LlmHealthGate.class);
            assertThat(ctx.getBean("llmProvider", LlmProvider.class).id()).isEqualTo("test");
            // Bound by name: the test double implements both interfaces, so a by-type lookup would be
            // ambiguous — consumers (gate, controller) inject with @Qualifier for the same reason.
            assertThat(ctx.getBean("embedder", Embedder.class).id()).isEqualTo("test");
        });
    }

    @Test
    void contextStartsWhenEmbeddingsUnreachable(@TempDir Path tmp) {
        // LLM probe healthy, embeddings probe fails: non-fatal (DD-005) — context still starts.
        // Give the LLM a healthy double and the embeddings axis a failing one to isolate the axes.
        TestDoubleProvider healthyLlm = TestDoubleProvider.create();
        TestDoubleProvider failingEmbedder = TestDoubleProvider.builder().failProbe(true).build();
        ProviderFactory split = new ProviderFactory(healthyLlm);
        split.registerEmbedder("test", auth -> failingEmbedder);

        AgentMemoryConfig config = configWithProviders(tmp, "test", "test");

        runner(config, split).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(LlmHealthGate.class);
        });
    }

    @Test
    void contextStartsWithNoEmbeddingsConfigured(@TempDir Path tmp) {
        ProviderFactory healthy = new ProviderFactory(TestDoubleProvider.create());
        AgentMemoryConfig config = configWithProviders(tmp, "test", null);

        runner(config, healthy).run(ctx -> {
            // No embeddings axis configured -> the 'embedder' @Bean returns null (no usable embedder),
            // but the server still starts (DD-005) and the health gate is built. Spring represents a
            // null @Bean with an internal NullBean sentinel, so we assert the observable behaviour: the
            // context started and the gate exists, and a name-qualified ObjectProvider yields nothing.
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(LlmHealthGate.class);
            assertThat(ctx.getBeanProvider(Embedder.class)
                    .stream().filter(e -> !(e instanceof LlmProvider)).findAny())
                    .as("no real (non-chat-provider) Embedder is available")
                    .isEmpty();
        });
    }

    private static boolean hasCauseOfType(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
