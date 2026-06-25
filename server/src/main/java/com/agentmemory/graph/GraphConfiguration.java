package com.agentmemory.graph;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the unified dependency-graph read service (issue #28). {@link GraphService} is the
 * read/aggregate side over the {@code links} table (maintained by #27 {@code WikiLinkService}, the
 * single writer) — it needs a JDBC {@link DataSource} / {@link JdbcTemplate} and is gated on one
 * ({@link ConditionalOnSingleCandidate}), the same pattern as {@code LinksConfiguration} /
 * {@code StoreConfiguration}, so the DB-less smoke context still loads. Declared
 * {@link AutoConfiguration#after()} the JDBC auto-config and listed in
 * {@code META-INF/spring/.../AutoConfiguration.imports} so the {@code JdbcTemplate} exists when the
 * condition is evaluated.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class GraphConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public GraphService graphService(JdbcTemplate jdbcTemplate) {
        return new GraphService(jdbcTemplate);
    }
}
