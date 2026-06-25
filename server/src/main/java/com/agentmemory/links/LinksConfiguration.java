package com.agentmemory.links;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the scoped-wikilinks beans (issue #27). The {@link WikiLinkParser} is pure (no DB) and always
 * available so #14 reindex and tests can reuse it; the {@link WikiLinkService} needs a JDBC
 * {@link DataSource} / {@link JdbcTemplate} and is gated on one ({@link ConditionalOnSingleCandidate})
 * — the same pattern as {@code StoreConfiguration}/{@code RecallConfiguration}, so the DB-less smoke
 * context still loads. Declared {@link AutoConfigureAfter} the JDBC auto-config and listed in
 * {@code META-INF/spring/.../AutoConfiguration.imports} so the {@code JdbcTemplate} exists when the
 * condition is evaluated.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class LinksConfiguration {

    @Bean
    @ConditionalOnMissingBean(WikiLinkParser.class)
    public WikiLinkParser wikiLinkParser() {
        return new WikiLinkParser();
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public WikiLinkService wikiLinkService(JdbcTemplate jdbcTemplate, WikiLinkParser parser) {
        return new WikiLinkService(jdbcTemplate, parser);
    }
}
