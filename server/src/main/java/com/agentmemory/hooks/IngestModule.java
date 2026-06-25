package com.agentmemory.hooks;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.store.ObservationWriter;
import com.agentmemory.store.StoreConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires the {@code /hook} ingest pipeline (issue #8). Declared as an {@link AutoConfiguration}
 * ordered after {@link StoreConfiguration} and gated {@link ConditionalOnBean} on the
 * {@link ObservationWriter}, mirroring how the store beans gate on a {@code DataSource}. Because
 * auto-configurations are evaluated after component scanning and in declared order, the
 * {@code @ConditionalOnBean} here is reliable — the writer bean (if any) is already registered when
 * this runs. In a DB-less context there is no writer, so the {@link IngestService} bean is absent and
 * {@code HookController} answers 503.
 *
 * <p>{@link Sanitizer} is a {@code @Component} (#9); {@link AgentMemoryConfig} comes from
 * {@code ConfigModule}. This module composes them with the writer into the single
 * {@link IngestService} the {@code HookController} drives.
 *
 * <p>An optional {@link ObservationListener} (issue #18: session consolidation reacts to
 * {@code session-end}/{@code pre-compact} captures) is injected by {@link ObjectProvider} so the
 * ingest path stays decoupled from — and does not require — the reacting module: when no listener
 * bean is present (e.g. the consolidate module is gated off in a DB-less context) the service runs
 * with a no-op, exactly as before.
 */
@AutoConfiguration(after = StoreConfiguration.class)
public class IngestModule {

    private static final Logger log = LoggerFactory.getLogger(IngestModule.class);

    /**
     * @param config    resolved config (its {@code ingest()} block sizes the bounded queue).
     * @param sanitizer the privacy boundary (#9).
     * @param writer    the single writer (#4/#8) the worker drains to.
     * @param listener  optional post-write hook (#18 consolidation); a no-op when absent.
     * @return the ingest service backing {@code POST /hook} and {@code /hook/batch}.
     */
    @Bean
    @ConditionalOnBean(ObservationWriter.class)
    public IngestService ingestService(
            AgentMemoryConfig config, Sanitizer sanitizer, ObservationWriter writer,
            ObjectProvider<ObservationListener> listener) {
        ObservationListener postWrite = listener.getIfAvailable();
        log.info(
                "ingest: bounded queue capacity={}, offer-timeout={}ms, post-write-listener={}",
                config.ingest().queueCapacity(), config.ingest().offerTimeoutMillis(),
                postWrite != null ? postWrite.getClass().getSimpleName() : "none");
        return new IngestService(config, sanitizer, writer, postWrite);
    }
}
