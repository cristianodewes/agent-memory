package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.AgentMemoryProperties;
import com.agentmemory.config.AutoScope;
import com.agentmemory.config.ProviderAuth;
import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.LlmModule;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.ProviderFactory;
import com.agentmemory.llm.TestDoubleProvider;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spring-context coverage for the recall provider seam (issue #146, Fase 5): adding the second
 * {@code recallLlmProvider} {@link LlmProvider} bean must not make {@link LlmProvider} injection
 * ambiguous, the three recall consumers must bind it (not the primary), and — because it is a
 * best-effort axis — it must be built but <em>never probed</em> at startup.
 *
 * <p>Boots {@link LlmModule} + the {@link LlmRecallConfiguration} auto-configuration over a stub
 * {@link DataSource}/{@link JdbcTemplate} (no real DB; the recall beans only need the DB-present gate
 * satisfied) and a scripted offline {@link ProviderFactory}. Mirrors {@code LlmModuleFailFastTest}'s
 * runner (test-supplied {@code ProviderFactory}, bean-definition overriding).
 */
class RecallProviderSeamWiringTest {

    private ApplicationContextRunner runner(AgentMemoryConfig config, ProviderFactory factory) {
        DataSource dataSource = mock(DataSource.class);
        return new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class, LlmRecallConfiguration.class))
                .withBean(AgentMemoryConfig.class, () -> config)
                .withBean(ProviderFactory.class, () -> factory)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withUserConfiguration(LlmModule.class);
    }

    private static AgentMemoryConfig config(Path dataDir) {
        AgentMemoryProperties props = new AgentMemoryProperties(
                new AgentMemoryProperties.Server("127.0.0.1", 0, "/", ""),
                new AgentMemoryProperties.Data(dataDir.toString()),
                new AgentMemoryProperties.Db("jdbc:postgresql://localhost/db", "u", ""),
                new AgentMemoryProperties.Llm(new ProviderAuth("test", "sk-chat", null, null)),
                new AgentMemoryProperties.Embeddings(ProviderAuth.NONE),
                new AgentMemoryProperties.Auth(false, "", List.of(), ""),
                new AgentMemoryProperties.Sanitization(65536, List.of()),
                new AgentMemoryProperties.Ingest(1024, 0),
                new AgentMemoryProperties.Decay(0.02, 1.0, 0.01, 1.0, 0.05, 30, 7),
                new AgentMemoryProperties.Scope(AutoScope.SINGLE_SLOT));
        return AgentMemoryConfig.resolve(props);
    }

    private static List<String> dependentsOf(ConfigurableApplicationContext context, String beanName) {
        ConfigurableListableBeanFactory bf = context.getBeanFactory();
        return List.of(bf.getDependentBeans(beanName));
    }

    @Test
    void defaultReusesPrimaryWithoutAmbiguityAndConsumersBindTheRecallProvider(@TempDir Path tmp) {
        // No agent-memory.recall.llm.auth -> recall reuses the primary chat provider.
        ProviderFactory factory = new ProviderFactory(TestDoubleProvider.create());

        runner(config(tmp), factory).run(ctx -> {
            assertThat(ctx).hasNotFailed();

            LlmProvider primary = ctx.getBean("llmProvider", LlmProvider.class);
            // No NoUniqueBeanDefinitionException despite two LlmProvider beans: @Primary picks the chat
            // provider for a bare by-type lookup.
            assertThat(ctx.getBean(LlmProvider.class)).isSameAs(primary);
            // Default: the recall bean is the very same primary instance (no second client).
            assertThat(ctx.getBean("recallLlmProvider", LlmProvider.class)).isSameAs(primary);

            ConfigurableApplicationContext src =
                    (ConfigurableApplicationContext) ctx.getSourceApplicationContext();
            // The three recall consumers bind recallLlmProvider...
            assertThat(dependentsOf(src, "recallLlmProvider"))
                    .contains("queryExpander", "candidateReranker", "briefSynthesizer");
            // ...and no longer the primary llmProvider (which keeps the heavyweight consumers + the gate).
            assertThat(dependentsOf(src, "llmProvider"))
                    .contains("recallLlmProvider", "llmHealthGate")
                    .doesNotContain("queryExpander", "candidateReranker", "briefSynthesizer");
        });
    }

    @Test
    void recallProviderIsBuiltButNotProbedAtStartup(@TempDir Path tmp) {
        // The chat provider is healthy; the recall provider (an independent model) would FAIL its probe.
        // If the health gate probed it, startup would abort — so a clean boot proves it is not probed.
        TestDoubleProvider healthy = TestDoubleProvider.create();
        TestDoubleProvider failingRecall = TestDoubleProvider.builder().failProbe(true).model("recall-x").build();
        ProviderFactory factory = new ProviderFactory(healthy);
        factory.registerLlm("test", auth -> "recall-x".equals(auth.model()) ? failingRecall : healthy);

        runner(config(tmp), factory)
                .withPropertyValues(
                        "agent-memory.recall.llm.auth.provider=test",
                        "agent-memory.recall.llm.auth.model=recall-x")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // The independent recall provider was built (distinct from the primary) but never probed.
                    assertThat(ctx.getBean("recallLlmProvider", LlmProvider.class)).isSameAs(failingRecall);
                    assertThat(ctx.getBean("llmProvider", LlmProvider.class)).isSameAs(healthy);
                    // Sanity: it really would have aborted startup had it been probed.
                    org.assertj.core.api.Assertions.assertThatThrownBy(failingRecall::probe)
                            .isInstanceOf(LlmException.class);
                });
    }
}
