package com.agentmemory.store;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the {@code store} beans (issues #12, #8). They need a JDBC {@link DataSource} /
 * {@link JdbcTemplate}; each is registered only when one is present
 * ({@link ConditionalOnSingleCandidate}) — the same gate Spring Boot's own
 * {@code JdbcTemplateAutoConfiguration} uses for the {@code JdbcTemplate} itself. This keeps the
 * DB-less smoke test (which excludes {@code DataSourceAutoConfiguration}) loading cleanly, while
 * production and the Testcontainers integration tests, where a DataSource exists, get fully wired
 * beans.
 *
 * <p>Declared as an {@link AutoConfiguration} ordered {@link AutoConfigureAfter} the JDBC + tx-manager
 * auto-configurations (and listed in {@code META-INF/spring/.../AutoConfiguration.imports}) so the
 * {@code DataSource}/{@code JdbcTemplate}/{@code PlatformTransactionManager} beans are already
 * registered when the conditions are evaluated — the ordering guarantee a component-scanned bean with
 * {@code @ConditionalOnBean} would not reliably have.
 */
@AutoConfiguration(after = {
    JdbcTemplateAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class
})
public class StoreConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public PageRepository pageRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPageRepository(jdbcTemplate);
    }

    /**
     * The single observation writer (issue #8, invariant #2). Gated on a {@code DataSource} exactly
     * like {@link #pageRepository}; consumes the auto-configured {@link JdbcTemplate} and
     * {@link PlatformTransactionManager} rather than minting its own, so there is one of each in the
     * container.
     *
     * @param jdbcTemplate the auto-configured JDBC template.
     * @param txManager    the auto-configured JDBC transaction manager.
     * @return the Postgres single-writer.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public ObservationWriter observationWriter(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager txManager) {
        return new PostgresObservationWriter(jdbcTemplate, txManager);
    }
}
