package com.agentmemory.forget;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.store.AuditWriter;
import com.agentmemory.store.RetentionScorer;
import com.agentmemory.wiki.WikiWriter;
import java.time.Clock;
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
 * Wires the forget-sweep beans (issue #25). DB-backed, so gated on a {@link DataSource}
 * ({@link ConditionalOnSingleCandidate}) like the store/recall/mcp configs and ordered after the JDBC
 * + tx-manager auto-configurations (listed in {@code META-INF/spring/.../AutoConfiguration.imports})
 * so the {@link JdbcTemplate} / {@link PlatformTransactionManager} exist when the conditions evaluate.
 *
 * <p>The sweep reuses the shared {@link RetentionScorer} (#24) for the cold decision, the
 * {@link AuditWriter} (#33 seam) for the audit row, the {@link WikiWriter} to remove a purged page's
 * markdown, and {@link AgentMemoryConfig#decay()} for the thresholds (cold / hard-delete-after /
 * recently-accessed). The {@code memory_forget_sweep} MCP tool ({@code MemorySweepTools}) is built and
 * registered in {@link com.agentmemory.mcp.McpConfiguration} (where the package-private MCP JSON
 * helper lives), consuming the {@link ForgetSweepService} bean published here.
 */
@AutoConfiguration(after = {
    JdbcTemplateAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class
})
public class ForgetConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public ForgetSweepRepository forgetSweepRepository(JdbcTemplate jdbcTemplate) {
        return new ForgetSweepRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public ForgetSweepService forgetSweepService(
            ForgetSweepRepository repo,
            RetentionScorer scorer,
            WikiWriter wikiWriter,
            AuditWriter auditWriter,
            PlatformTransactionManager txManager,
            Clock clock,
            AgentMemoryConfig config) {
        return new ForgetSweepService(
                repo, scorer, wikiWriter, auditWriter, txManager, clock, config.decay());
    }
}
