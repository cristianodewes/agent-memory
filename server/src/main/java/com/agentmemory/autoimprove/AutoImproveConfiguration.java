package com.agentmemory.autoimprove;

import com.agentmemory.consolidate.Consolidator;
import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.eval.EvalGate;
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
 * Wires the auto-improve loop (issue #30) end-to-end: the {@code pending_writes} repository, the
 * scheduler-state repository (watermark + claims), the approval {@link AutoImproveGate} (gated by the #31
 * {@link EvalGate}), the {@link ProposalApplier} that applies approved proposals through the #19/#20
 * {@link MemoryWriteService}, and the production {@link ProposalSource} — the #19 {@link Consolidator} in
 * propose-only mode ({@link ConsolidationProposalSource}).
 *
 * <p>DB-backed beans are gated on a {@link DataSource} ({@link ConditionalOnSingleCandidate}, like the
 * store/MCP modules) so the DB-less smoke context still loads. Ordered after the JDBC, MCP, consolidate and
 * eval auto-configs so the {@link JdbcTemplate}, {@link MemoryWriteService}, {@link Consolidator} and
 * {@link EvalGate} exist when the conditions are evaluated. The scheduler tick and the
 * {@code memory_auto_improve} MCP tool are added on top of these beans.
 */
@AutoConfiguration(
        after = JdbcTemplateAutoConfiguration.class,
        afterName = {
            "com.agentmemory.mcp.McpConfiguration",
            "com.agentmemory.consolidate.ConsolidateConfiguration",
            "com.agentmemory.eval.EvalGateConfiguration"
        })
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
    @ConditionalOnBean({JdbcPendingWriteRepository.class, ProposalApplier.class, EvalGate.class})
    public AutoImproveGate autoImproveGate(
            JdbcPendingWriteRepository pending, ProposalApplier applier, AutoImproveProperties props,
            EvalGate evalGate) {
        return new AutoImproveGate(pending, applier, props, evalGate);
    }

    /**
     * The production {@link ProposalSource}: the #19 {@link Consolidator} in <em>propose-only</em> mode
     * ({@link ConsolidationProposalSource}). Present only when the consolidator is wired (it needs the store
     * + MCP read layer + the required LLM); otherwise the scheduler has no source and stays inert. Unit
     * tests supply a fake source directly, so this bean is the only production wiring of the seam.
     *
     * <p>{@code multiPage=true}: a session is proposed as its full durable fan-out, each page an
     * independently approvable {@code pending_writes} row — distinct from the automatic session-end
     * consolidation ({@code ConsolidationObservationListener}), which writes the fan-out directly.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(Consolidator.class)
    public ProposalSource consolidationProposalSource(Consolidator consolidator) {
        return new ConsolidationProposalSource(consolidator, true);
    }

    /**
     * The out-of-band review scheduler. Created when its data + gate beans exist, but inert by default:
     * off unless {@code agent-memory.auto-improve.scheduler.enabled=true}, and a no-op even then unless a
     * {@link ProposalSource} is wired (the #19 consolidation source above; absent it — e.g. no LLM — a tick
     * logs and skips). The source is passed as a supplier over an {@link ObjectProvider} so the scheduler
     * resolves whatever source is present without re-wiring.
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
