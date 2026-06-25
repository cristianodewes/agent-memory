package com.agentmemory.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves the configuration layering required by issue #2:
 * built-in defaults &rarr; external {@code agent-memory.yml} &rarr; environment/system overrides.
 *
 * <p>Boots a real (non-web) {@link org.springframework.boot.SpringApplication} so the actual
 * config-data import phase loads the external file via {@code spring.config.import} and the real
 * property-source ordering applies — a bare {@code ApplicationContextRunner} skips that phase, so
 * it could not faithfully test file precedence. System properties stand in for environment
 * variables: both occupy the same high-precedence tier that outranks an imported config file, so a
 * system property beating a file value demonstrates exactly the {@code env > file} guarantee.
 */
class ConfigPrecedenceTest {

    /** {@code spring.config.import} needs forward slashes even on Windows. */
    private static String importOf(Path file) {
        return "file:" + file.toString().replace('\\', '/');
    }

    /**
     * Boot the config layer with the given data dir (so {@link ConfigModule} can create it) plus
     * any extra inline properties / config-file imports, returning the resolved config.
     */
    private AgentMemoryProperties boot(Path dataDir, String... extraProps) {
        String[] props = new String[extraProps.length + 2];
        props[0] = "spring.main.web-application-type=none";
        props[1] = "agent-memory.data.dir=" + dataDir.toString().replace('\\', '/');
        System.arraycopy(extraProps, 0, props, 2, extraProps.length);

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ConfigModule.class)
                .web(WebApplicationType.NONE)
                .properties(props)
                .run()) {
            // Return the bound raw properties; AgentMemoryConfig (resolved) is also asserted to exist.
            assertThat(ctx.getBeanProvider(AgentMemoryConfig.class).getIfAvailable()).isNotNull();
            return ctx.getBean(AgentMemoryProperties.class);
        }
    }

    private static Path writeConfig(Path dir, String yaml) throws IOException {
        Path file = dir.resolve("agent-memory.yml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void usesBuiltInDefaultsWhenNothingElseIsSet(@TempDir Path tmp) {
        AgentMemoryProperties props = boot(tmp.resolve("data"));

        assertThat(props.server().address()).isEqualTo("127.0.0.1");
        assertThat(props.server().port()).isEqualTo(8080);
        assertThat(props.server().basePath()).isEqualTo("/");
        assertThat(props.auth().enabled()).isFalse();
        assertThat(props.llm().auth()).isEqualTo(ProviderAuth.NONE);
    }

    @Test
    void fileOverridesDefaults(@TempDir Path tmp) throws IOException {
        Path file = writeConfig(tmp, """
                agent-memory:
                  server:
                    port: 9999
                  llm:
                    auth:
                      provider: anthropic
                      model: claude-opus-4-8
                """);

        AgentMemoryProperties props = boot(tmp.resolve("data"), "spring.config.import=" + importOf(file));

        assertThat(props.server().port()).isEqualTo(9999);                 // from file
        assertThat(props.llm().auth().provider()).isEqualTo("anthropic");  // from file
        assertThat(props.llm().auth().model()).isEqualTo("claude-opus-4-8");
        assertThat(props.server().address()).isEqualTo("127.0.0.1");       // still default
    }

    @Test
    void environmentTierOverridesFile(@TempDir Path tmp) throws IOException {
        Path file = writeConfig(tmp, """
                agent-memory:
                  server:
                    port: 9999
                    address: 10.0.0.1
                """);

        // A real system property -> same precedence tier as an env var, above the imported file.
        System.setProperty("agent-memory.server.port", "7000");
        try {
            AgentMemoryProperties props =
                    boot(tmp.resolve("data"), "spring.config.import=" + importOf(file));

            assertThat(props.server().port()).isEqualTo(7000);          // override tier wins
            assertThat(props.server().address()).isEqualTo("10.0.0.1"); // file still applies
        } finally {
            System.clearProperty("agent-memory.server.port");
        }
    }

    @Test
    void fullChainDefaultsFileThenEnv(@TempDir Path tmp) throws IOException {
        // address: comes from default; port: from file; base-path: from env tier.
        Path file = writeConfig(tmp, """
                agent-memory:
                  server:
                    port: 9001
                """);

        System.setProperty("agent-memory.server.base-path", "/mem");
        try {
            AgentMemoryProperties props =
                    boot(tmp.resolve("data"), "spring.config.import=" + importOf(file));

            assertThat(props.server().address()).isEqualTo("127.0.0.1"); // default
            assertThat(props.server().port()).isEqualTo(9001);           // file
            assertThat(props.server().basePath()).isEqualTo("/mem");     // env tier
        } finally {
            System.clearProperty("agent-memory.server.base-path");
        }
    }
}
