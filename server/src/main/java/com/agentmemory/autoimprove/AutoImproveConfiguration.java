package com.agentmemory.autoimprove;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.mcp.MemoryWriteService;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the auto-improve loop (issue #30): the {@code pending_writes} repository, the scheduler-state
 * repository (watermark + claims), the approval {@link AutoImproveGate}, and the {@link ProposalApplier}
 * that applies approved proposals through the #19/#20 {@link MemoryWriteService}.
 *
 * <p>DB-backed beans are gated on a {@link DataSource} ({@link ConditionalOnSingleCandidate}, like the
 * store/MCP modules) so the DB-less smoke context still loads. Ordered after the JDBC and MCP
 * auto-configs so the {@link JdbcTemplate} and {@link MemoryWriteService} exist when the conditions are
 * evaluated. The scheduler tick and the {@code memory_auto_improve} MCP tool are added on top of these
 * beans.
 */
@AutoConfiguration(
        after = JdbcTemplateAutoConfiguration.class,
        afterName = "com.agentmemory.mcp.McpConfiguration")
@EnableConfigurationProperties(AutoImproveProperties.class)
public class AutoImproveConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public JdbcPendingWriteRepository pendingWriteRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcPendingWriteRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public JdbcAutoImproveStateRepository autoImproveStateRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcAutoImproveStateRepository(jdbcTemplate);
    }

    /**
     * Applies an approved proposal through the normal durable-write path (atomic page write + git commit
     * + audit). Present only when the write service is wired; otherwise the gate can still hold proposals
     * but cannot auto-apply.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(MemoryWriteService.class)
    public ProposalApplier proposalApplier(MemoryWriteService writes) {
        return (scope, write) -> {
            Identity id = Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(write.path()));
            writes.writePages(
                    List.of(new MemoryWriteService.PageWrite(id, write.title(), write.body())),
                    "auto-improve");
        };
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({JdbcPendingWriteRepository.class, ProposalApplier.class})
    public AutoImproveGate autoImproveGate(
            JdbcPendingWriteRepository pending, ProposalApplier applier, AutoImproveProperties props) {
        return new AutoImproveGate(pending, applier, props);
    }

    /**
     * The out-of-band review scheduler. Created when its data + gate beans exist, but inert by default:
     * off unless {@code agent-memory.auto-improve.scheduler.enabled=true}, and a no-op even then until a
     * {@link ProposalSource} is wired (deferred to #29/#19). The source is passed as a supplier over an
     * {@link ObjectProvider} so the scheduler resolves whatever source lands later without re-wiring.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({JdbcAutoImproveStateRepository.class, AutoImproveGate.class})
    public AutoImproveScheduler autoImproveScheduler(
            JdbcAutoImproveStateRepository state,
            AutoImproveGate gate,
            ObjectProvider<ProposalSource> source,
            AutoImproveProperties props) {
        return new AutoImproveScheduler(state, gate, source::getIfAvailable, props);
    }
}
