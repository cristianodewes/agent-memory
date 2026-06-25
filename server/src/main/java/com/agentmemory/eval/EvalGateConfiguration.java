package com.agentmemory.eval;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the executable {@link EvalGate} (issue #31). The bean is always present — even when the gate
 * is disabled — so the auto-improve loop (#30) can inject it unconditionally and call
 * {@link EvalGate#evaluate}; a disabled or unconfigured gate simply returns
 * {@link EvalVerdict.Decision#SKIPPED}. No {@code DataSource} dependency: the gate operates on a proposal
 * value, not the store.
 */
@AutoConfiguration
@EnableConfigurationProperties(EvalGateProperties.class)
public class EvalGateConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EvalGate evalGate(EvalGateProperties properties) {
        return new EvalGate(properties);
    }
}
