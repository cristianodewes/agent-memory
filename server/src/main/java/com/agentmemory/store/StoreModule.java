package com.agentmemory.store;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the {@code store} layer (ARCHITECTURE §6): the JDBC access template, a transaction
 * template, and the single-writer {@link ObservationWriter}. Follows the established
 * {@code @Configuration} + {@code @Bean} module pattern (cf. {@code LlmModule}, {@code ConfigModule})
 * rather than component scanning of the implementations — dependencies are explicit (invariant #12,
 * no static singletons).
 *
 * <p>The {@code DataSource} and {@code PlatformTransactionManager} are Spring Boot
 * auto-configurations (from {@code spring-boot-starter-jdbc} + the resolved
 * {@code spring.datasource.*}); this module only adapts them into the store's collaborators. Each
 * bean method takes its infrastructure dependency as an {@link ObjectProvider} and returns
 * {@code null} when it is absent — so a deliberately DB-less context (the web/config smoke test
 * excludes {@code DataSourceAutoConfiguration}) cleanly registers <em>no</em> store beans instead of
 * failing to start. A {@code null} return from a {@code @Bean} method simply registers no bean.
 */
@Configuration(proxyBeanMethods = false)
public class StoreModule {

    private static final Logger log = LoggerFactory.getLogger(StoreModule.class);

    /**
     * @param dataSource the single application {@code DataSource}, if one is configured.
     * @return a {@link JdbcTemplate}, or {@code null} when there is no {@code DataSource}.
     */
    @Bean
    @Nullable
    public JdbcTemplate jdbcTemplate(ObjectProvider<DataSource> dataSource) {
        DataSource ds = dataSource.getIfAvailable();
        return ds == null ? null : new JdbcTemplate(ds);
    }

    /**
     * @param txManager the auto-configured JDBC transaction manager, if present.
     * @return a {@link TransactionTemplate}, or {@code null} when there is no transaction manager.
     */
    @Bean
    @Nullable
    public TransactionTemplate transactionTemplate(ObjectProvider<PlatformTransactionManager> txManager) {
        PlatformTransactionManager tm = txManager.getIfAvailable();
        return tm == null ? null : new TransactionTemplate(tm);
    }

    /**
     * The single writer (invariant #2). Concrete Postgres implementation of the {@code core}-level
     * {@link ObservationWriter} seam introduced with the sanitizer boundary (#9).
     *
     * @param jdbc the JDBC template, if the store layer is wired.
     * @param tx   the transaction template, if the store layer is wired.
     * @return the process-wide observation writer, or {@code null} without a database.
     */
    @Bean
    @Nullable
    public ObservationWriter observationWriter(
            ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        JdbcTemplate j = jdbc.getIfAvailable();
        TransactionTemplate t = tx.getIfAvailable();
        if (j == null || t == null) {
            return null;
        }
        log.info("observation writer: Postgres single-writer");
        return new PostgresObservationWriter(j, t);
    }
}
