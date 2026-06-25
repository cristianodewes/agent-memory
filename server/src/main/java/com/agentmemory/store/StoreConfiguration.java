package com.agentmemory.store;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code store} beans (issue #12). The page repository needs a JDBC {@link DataSource} /
 * {@link JdbcTemplate}; it is registered only when one is present ({@link ConditionalOnSingleCandidate})
 * — the same gate Spring Boot's own {@code JdbcTemplateAutoConfiguration} uses for the
 * {@code JdbcTemplate} itself. This keeps the DB-less smoke test (which excludes
 * {@code DataSourceAutoConfiguration}) loading cleanly, while production and the Testcontainers
 * integration tests, where a DataSource exists, get a fully wired repository.
 *
 * <p>Declared as an {@link AutoConfiguration} ordered {@link AutoConfigureAfter} the JDBC
 * auto-configuration (and listed in {@code META-INF/spring/.../AutoConfiguration.imports}) so the
 * {@code DataSource}/{@code JdbcTemplate} beans are already registered when the condition is
 * evaluated — the ordering guarantee a component-scanned {@code @Repository} with
 * {@code @ConditionalOnBean} would not reliably have.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class StoreConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public PageRepository pageRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPageRepository(jdbcTemplate);
    }
}
