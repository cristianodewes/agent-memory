package com.agentmemory.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies {@link ConfigModule} publishes a single resolved {@link AgentMemoryConfig} bean and
 * that booting the context creates the data-dir tree (the "constructed once + injected" and
 * "created at startup" acceptance criteria). Failure-to-start on a bad data dir is asserted too.
 */
class ConfigModuleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(ConfigModule.class);

    @Test
    void publishesSingleResolvedConfigBeanAndCreatesLayout(@TempDir Path tmp) {
        Path dataDir = tmp.resolve("data");

        runner.withPropertyValues("agent-memory.data.dir=" + dataDir.toString().replace('\\', '/'))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AgentMemoryConfig.class);
                    AgentMemoryConfig config = ctx.getBean(AgentMemoryConfig.class);
                    assertThat(config.dataDir())
                            .isEqualTo(dataDir.toAbsolutePath().normalize())
                            .isDirectory();
                    assertThat(config.wikiDir()).isDirectory();
                    assertThat(config.rawDir()).isDirectory();
                    assertThat(config.dbDir()).isDirectory();
                    assertThat(config.logsDir()).isDirectory();
                });
    }

    @Test
    void contextFailsToStartWhenDataDirIsUnusable(@TempDir Path tmp) throws Exception {
        // A regular file sitting where a directory must be created -> startup must abort.
        Path blocker = tmp.resolve("blocker");
        java.nio.file.Files.writeString(blocker, "x");
        Path target = blocker.resolve("child");

        runner.withPropertyValues("agent-memory.data.dir=" + target.toString().replace('\\', '/'))
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    // ConfigException is in the cause chain (it wraps the low-level IO error, which
                    // is the actual root cause), so assert on the chain rather than the deepest node.
                    assertThat(hasCauseOfType(ctx.getStartupFailure(), ConfigException.class))
                            .as("startup failure should be caused by a ConfigException")
                            .isTrue();
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
