package com.agentmemory.store;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.AgentMemoryProperties;
import com.agentmemory.core.ActorResolver;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the {@code store} beans (issues #12, #8, #24). The DB-backed beans
 * ({@link #pageRepository}, {@link #observationWriter}) need a JDBC {@link DataSource} /
 * {@link JdbcTemplate} and are each registered only when one is present
 * ({@link ConditionalOnSingleCandidate}) — the same gate Spring Boot's own
 * {@code JdbcTemplateAutoConfiguration} uses for the {@code JdbcTemplate} itself. This keeps the
 * DB-less smoke test (which excludes {@code DataSourceAutoConfiguration}) loading cleanly, while
 * production and the Testcontainers integration tests, where a DataSource exists, get fully wired
 * beans. The pure-math {@link #retentionScorer} (#24) has no DataSource dependency and is always
 * registered, so recall (#15) and the forget sweep (#25) share one decay implementation regardless
 * of whether a DB is wired.
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
     * The {@code audit_log} writer (issue #33). The shared seam for recording mutations with their
     * before/after identity (lifecycle ops, and reusable by other writers); gated on a
     * {@code DataSource} like the others.
     *
     * <p>The {@link ActorResolver} (issue #39) stamps each row with the authenticated user. Resolved
     * through an {@link ObjectProvider} so this bean still wires when the security auto-config is
     * absent (a DB-only context with no auth), falling back to {@link ActorResolver#NONE} (no actor).
     *
     * @param jdbcTemplate the auto-configured JDBC template.
     * @param actors       provider for the current-actor resolver (may resolve to none).
     * @return the audit-log writer.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public AuditWriter auditWriter(JdbcTemplate jdbcTemplate, ObjectProvider<ActorResolver> actors) {
        return new JdbcAuditWriter(jdbcTemplate, actors.getIfAvailable(() -> ActorResolver.NONE));
    }

    /**
     * The {@code handoffs} repository (issue #22): single-use, one-open-per-project typed handoffs.
     * Gated on a {@code DataSource} exactly like {@link #pageRepository}.
     *
     * @param jdbcTemplate the auto-configured JDBC template.
     * @return the handoff store.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public HandoffRepository handoffRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcHandoffRepository(jdbcTemplate);
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

    /**
     * The clock the decay math reads "now" from (UTC). Declared
     * {@link ConditionalOnMissingBean @ConditionalOnMissingBean} so a test can substitute a fixed
     * clock to assert the decay curve over elapsed time deterministically.
     *
     * @return the system UTC clock.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock retentionClock() {
        return Clock.systemUTC();
    }

    /**
     * The shared retention/decay scorer (issue #24, ARCHITECTURE §3.3) — the single implementation of
     * the {@code salience·exp(−λ·Δt) + σ·log(1+access)·exp(−μ·days_since_access)} curve that recall
     * ranking (#15) and the forget sweep (#25) both consume. Its λ/σ/μ + cold threshold come from the
     * single config (#2) via {@link AgentMemoryConfig}; no DataSource is required, so it is always
     * available.
     *
     * @param config the resolved server config (source of the decay knobs).
     * @param clock  the clock "now" is read from.
     * @return the retention scorer.
     */
    @Bean
    @ConditionalOnMissingBean(RetentionScorer.class)
    public RetentionScorer retentionScorer(AgentMemoryConfig config, Clock clock) {
        AgentMemoryProperties.Decay d = config.decay();
        RetentionParameters params = new RetentionParameters(
                d.lambda(), d.sigma(), d.mu(), d.defaultSalience(), d.coldThreshold());
        return new RetentionScorer(params, clock);
    }
}
