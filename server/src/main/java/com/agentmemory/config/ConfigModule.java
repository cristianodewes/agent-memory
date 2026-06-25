package com.agentmemory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the configuration layer into the Spring context (ARCHITECTURE.md §6, §7).
 *
 * <p>Enables the single {@link AgentMemoryProperties} binding and publishes the resolved,
 * validated {@link AgentMemoryConfig} as an explicit bean — dependencies are injected, never read
 * from a static singleton (invariant #12). Resolution runs once at context start (invariant #1);
 * if the data dir is unusable, {@link AgentMemoryConfig#resolve} throws and startup fails fast.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentMemoryProperties.class)
public class ConfigModule {

    private static final Logger log = LoggerFactory.getLogger(ConfigModule.class);

    /**
     * Resolve the raw properties into the validated config and log the canonical data dir once
     * (invariant #11). Bean creation is the single config load (invariant #1).
     *
     * @param properties the bound, immutable raw properties.
     * @return the resolved configuration shared by every subsystem.
     */
    @Bean
    public AgentMemoryConfig agentMemoryConfig(AgentMemoryProperties properties) {
        AgentMemoryConfig config = AgentMemoryConfig.resolve(properties);
        log.info("agent-memory data dir: {}", config.dataDir());
        log.info("agent-memory config resolved: server={}, db={}, auth={}",
                config.server(), config.db(), config.auth());
        return config;
    }
}
