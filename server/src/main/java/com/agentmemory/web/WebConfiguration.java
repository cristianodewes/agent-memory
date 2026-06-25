package com.agentmemory.web;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code /api/v1} web-API support beans (issue #35). The only DB-backed bean the API adds
 * is {@link WebReadRepository} (workspace/project directory + paginated page listings); like the
 * store/recall/MCP configs it is gated on a {@link DataSource}
 * ({@link ConditionalOnSingleCandidate}) so the DB-less smoke context still loads, and is declared an
 * {@link AutoConfiguration} ordered after the JDBC auto-config (listed in
 * {@code META-INF/spring/.../AutoConfiguration.imports}) so the {@link JdbcTemplate} exists when the
 * condition is evaluated.
 *
 * <p>{@code ApiV1Controller} / {@code ApiV1ExceptionHandler} are component-scanned {@code @RestController}
 * beans, not registered here: the controller injects its DB-backed collaborators through
 * {@code ObjectProvider}, so it constructs even without a {@code DataSource} (and answers 503),
 * exactly like {@code HookController}.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class WebConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public WebReadRepository webReadRepository(JdbcTemplate jdbcTemplate) {
        return new WebReadRepository(jdbcTemplate);
    }
}
