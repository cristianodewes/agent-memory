package com.agentmemory.hooks;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.store.ObservationWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

/**
 * Wiring for the {@code /hook} ingest pipeline (issue #8). Follows the established
 * {@code @Configuration} + {@code @Bean} module pattern (cf. {@code LlmModule},
 * {@code com.agentmemory.store.StoreModule}); dependencies are explicit (invariant #12).
 *
 * <p>{@link Sanitizer} is itself a {@code @Component} (#9); the {@link ObservationWriter} comes from
 * {@code StoreModule}. This module composes them into the single {@link IngestService} the
 * {@code HookController} drives. The writer is injected as an {@link ObjectProvider}: when there is
 * no store layer (a DB-less context), the {@link IngestService} bean is simply not registered (the
 * {@code @Bean} method returns {@code null}), and {@code HookController} answers 503.
 */
@Configuration(proxyBeanMethods = false)
public class IngestModule {

    private static final Logger log = LoggerFactory.getLogger(IngestModule.class);

    /**
     * @param config    resolved config (its {@code ingest()} block sizes the bounded queue).
     * @param sanitizer the privacy boundary (#9).
     * @param writer    the single writer (#4) the worker drains to, if the store layer is wired.
     * @return the ingest service backing {@code POST /hook} and {@code /hook/batch}, or {@code null}
     *     when there is no store layer.
     */
    @Bean
    @Nullable
    public IngestService ingestService(
            AgentMemoryConfig config, Sanitizer sanitizer, ObjectProvider<ObservationWriter> writer) {
        ObservationWriter w = writer.getIfAvailable();
        if (w == null) {
            log.info("ingest: no store layer wired; /hook will answer 503");
            return null;
        }
        log.info(
                "ingest: bounded queue capacity={}, offer-timeout={}ms",
                config.ingest().queueCapacity(), config.ingest().offerTimeoutMillis());
        return new IngestService(config, sanitizer, w);
    }
}
