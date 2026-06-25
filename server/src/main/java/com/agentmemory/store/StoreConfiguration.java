package com.agentmemory.store;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
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
     * The {@code links} repository (issue #14). First writer of the wikilink graph the recall graph
     * arm reads; gated on a {@code DataSource} exactly like {@link #pageRepository}.
     *
     * @param jdbcTemplate the auto-configured JDBC template.
     * @return the links writer/maintenance repository.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public LinkRepository linkRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcLinkRepository(jdbcTemplate);
    }

    /**
     * The {@code audit_log} writer (issue #33). The shared seam for recording mutations with their
     * before/after identity (lifecycle ops, and reusable by other writers); gated on a
     * {@code DataSource} like the others.
     *
     * @param jdbcTemplate the auto-configured JDBC template.
     * @return the audit-log writer.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public AuditWriter auditWriter(JdbcTemplate jdbcTemplate) {
        return new JdbcAuditWriter(jdbcTemplate);
    }

    /**
     * The single observation writer (issue #8, invariant #2). Gated on a {@code DataSource} exactly
     * like {@link #pageRepository}; consumes the auto-configured {@link JdbcTemplate} and
     * {@link PlatformTransactionManager} rather than minting its own, so there is one of each in the
     * container.
     *
     * <p>The optional {@link ObservationSideEffect} (issue #11's {@code log.md} + {@code raw/} writer,
     * provided by the {@code wiki} layer as {@code SessionLog}) is resolved through an
     * {@link ObjectProvider} so wiring is order-independent and the writer still constructs when the
     * wiki layer is absent (DB-only — e.g. the smoke configuration). When present it runs inside the
     * write transaction so the row and the file writes commit together.
     *
     * @param jdbcTemplate the auto-configured JDBC template.
     * @param txManager    the auto-configured JDBC transaction manager.
     * @param sideEffect   provider for the optional #11 file side effect (may resolve to none).
     * @return the Postgres single-writer.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public ObservationWriter observationWriter(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager txManager,
            ObjectProvider<ObservationSideEffect> sideEffect) {
        return new PostgresObservationWriter(jdbcTemplate, txManager, sideEffect.getIfAvailable());
    }
}
