package com.agentmemory.recall;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code recall} beans (issue #15). Like {@link com.agentmemory.store.StoreConfiguration},
 * the DB-backed beans ({@link RecallRepository}, {@link HybridRecallService}) are registered only
 * when a JDBC {@link DataSource} / {@link JdbcTemplate} is present
 * ({@link ConditionalOnSingleCandidate}) — the same gate Spring Boot uses for {@code JdbcTemplate} —
 * so the DB-less smoke test (which excludes {@code DataSourceAutoConfiguration}) still loads cleanly,
 * while production and the Testcontainers tests get a fully wired {@link RecallService}.
 *
 * <p>The {@link RrfFusion} strategy carries no DB dependency and is a plain {@code @Component} (so a
 * future arm/re-ranker can replace it via the {@link Fusion} interface); it is injected here.
 * Declared {@link AutoConfigureAfter} the JDBC auto-configuration and listed in
 * {@code META-INF/spring/.../AutoConfiguration.imports} so the {@code JdbcTemplate} bean exists when
 * the condition is evaluated.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class RecallConfiguration {

    /**
     * The rank-fusion strategy. Default RRF; declared {@code @ConditionalOnMissingBean} so a later
     * issue (#16 vector arm, #21 re-rank) can override it by publishing its own {@link Fusion}.
     * Carries no DB dependency, so it is available even in the DB-less smoke context.
     *
     * @return the default {@link RrfFusion}.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(Fusion.class)
    public Fusion rrfFusion() {
        return new RrfFusion();
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public RecallRepository recallRepository(JdbcTemplate jdbcTemplate) {
        return new RecallRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public RecallService recallService(RecallRepository recallRepository, Fusion fusion) {
        return new HybridRecallService(recallRepository, fusion);
    }
}
