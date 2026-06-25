package com.agentmemory.curate;

import com.agentmemory.graph.GraphService;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the rule-based curator (issue #29). Both beans are DB-backed and gated on a single
 * {@link DataSource} ({@link ConditionalOnSingleCandidate}), the same pattern as the rest, so the
 * DB-less smoke context still loads. {@link CuratorService} also depends on the #28
 * {@link GraphService} (the dangling-reference lint) — itself DataSource-gated — for the
 * cross-project-dangling rule. Declared {@link AutoConfiguration#after()} the JDBC auto-config and
 * listed in {@code META-INF/spring/.../AutoConfiguration.imports}.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class CuratorConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public CuratorRepository curatorRepository(JdbcTemplate jdbcTemplate) {
        return new CuratorRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public CuratorService curatorService(CuratorRepository curatorRepository, GraphService graphService) {
        return new CuratorService(curatorRepository, graphService);
    }
}
