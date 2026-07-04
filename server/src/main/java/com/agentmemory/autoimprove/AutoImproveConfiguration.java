package com.agentmemory.autoimprove;

import com.agentmemory.consolidate.SessionObservationReader;
import com.agentmemory.curate.CuratorService;
import com.agentmemory.eval.EvalGate;
import com.agentmemory.forget.ForgetSweepService;
import com.agentmemory.links.WikiLinkParser;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.LlmRateLimitGate;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.recall.RecallService;
import com.agentmemory.store.PageRepository;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * {@link MemoryWriteService}, and the production {@link ProposalSource} — the #29 {@link CuratorService}
 * adapter ({@link CuratorProposalSource}).
 *
 * <p>DB-backed beans are gated on a {@link DataSource} ({@link ConditionalOnSingleCandidate}, like the
 * store/MCP modules) so the DB-less smoke context still loads. Ordered after the JDBC, MCP, curator and
 * eval auto-configs so the {@link JdbcTemplate}, {@link MemoryWriteService}, {@link CuratorService} and
 * {@link EvalGate} exist when the conditions are evaluated. The scheduler tick and the
 * {@code memory_auto_improve} MCP tool are added on top of these beans.
 */
@AutoConfiguration(
        after = JdbcTemplateAutoConfiguration.class,
        afterName = {
            "com.agentmemory.mcp.McpConfiguration",
            "com.agentmemory.curate.CuratorConfiguration",
            "com.agentmemory.eval.EvalGateConfiguration",
            "com.agentmemory.forget.ForgetConfiguration",
            "com.agentmemory.links.LinksConfiguration",
            "com.agentmemory.consolidate.ConsolidateConfiguration",
            "com.agentmemory.recall.RecallConfiguration"
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
     * Applies an approved proposal by dispatching on its {@code kind} (issue #101): content
     * {@code page.edit} through the normal durable-write path (atomic page write + git commit + audit),
     * {@code page.forget} through the {@link ForgetSweepService forget sweep}, {@code link.fix} by pruning
     * the dangling wikilink and re-writing the page. Present only when the write/forget/link beans are
     * wired (always co-present with a {@link DataSource}); otherwise the gate can still hold proposals but
     * cannot auto-apply.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({
        MemoryWriteService.class, ForgetSweepService.class, WikiLinkParser.class, PageRepository.class
    })
    public ProposalApplier proposalApplier(
            MemoryWriteService writes,
            ForgetSweepService forget,
            WikiLinkParser parser,
            PageRepository pages) {
        return new DispatchingProposalApplier(
                new ContentProposalApplier(writes),
                new ForgetProposalApplier(forget),
                new LinkFixProposalApplier(pages, parser, writes));
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
     * The production {@link ProposalSource}: the #29 {@link CuratorService} adapter ({@link
     * CuratorProposalSource}). Present only when the curator is wired (its rule engine needs the store +
     * the #28 graph); otherwise the scheduler has no source and stays inert. Unit tests supply a fake
     * source directly, so this bean is the only production wiring of the seam.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({CuratorService.class, PageRepository.class})
    public ProposalSource curatorProposalSource(CuratorService curator, PageRepository pages) {
        return new CuratorProposalSource(curator, pages);
    }

    /**
     * The LLM session-review engine (issue #150) — the second {@link ProposalSource}, composing with the
     * rule-based curator adapter above under its own claim namespace ({@code source='review'}, V16).
     * Binds the {@code reviewLlmProvider} seam by name (route review to a cheap model, default = the
     * rate-limit-tracking primary, #148) and the plain hybrid {@code recallService} for staging-time
     * dedup (never the {@code @Primary} LLM-recall decorator — dedup must not burn extra LLM calls).
     * Disabled via {@code agent-memory.auto-improve.review.enabled=false} (default on).
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({SessionObservationReader.class, PageRepository.class, LlmRateLimitGate.class})
    public SessionReviewProposalSource sessionReviewProposalSource(
            SessionObservationReader reader,
            PageRepository pages,
            @Qualifier("reviewLlmProvider") LlmProvider reviewLlm,
            @Qualifier("recallService") ObjectProvider<RecallService> recall,
            LlmRateLimitGate rateLimitGate,
            AutoImproveProperties props) {
        return new SessionReviewProposalSource(
                reader, pages, reviewLlm, recall::getIfAvailable, rateLimitGate, props);
    }

    /**
     * The out-of-band review scheduler. Created when its data + gate beans exist; on by default (#150,
     * {@code agent-memory.auto-improve.scheduler.enabled}), and a no-op until at least one
     * {@link ProposalSource} is wired. The sources are passed as a supplier over an
     * {@link ObjectProvider} so the scheduler resolves whatever engines are present without re-wiring.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({JdbcAutoImproveStateRepository.class, AutoImproveGate.class})
    public AutoImproveScheduler autoImproveScheduler(
            JdbcAutoImproveStateRepository state,
            AutoImproveGate gate,
            ObjectProvider<ProposalSource> sources,
            AutoImproveProperties props) {
        return new AutoImproveScheduler(state, gate, () -> sources.orderedStream().toList(), props);
    }

    // --- issue #101: scope-level curator corrective-action loop ------------------------------------

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public CuratorActionRepository curatorActionRepository(JdbcTemplate jdbcTemplate) {
        return new CuratorActionRepository(jdbcTemplate);
    }

    /**
     * The scope-level corrective-action source (issue #101): maps each actionable #29 curator finding to
     * a {@code page.forget}/{@code link.fix} proposal. Present only when the curator is wired (same
     * condition as the #100 content {@link CuratorProposalSource}); tests construct it directly.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean(CuratorService.class)
    public CuratorActionProposalSource curatorActionProposalSource(CuratorService curator) {
        return new CuratorActionProposalSource(curator);
    }

    /**
     * The out-of-band scope-level curator-action loop. Created when its data + gate + source beans exist,
     * but inert by default: off unless {@code agent-memory.auto-improve.curator-actions.enabled=true}.
     * Distinct from {@link AutoImproveScheduler} (per-finished-session); this one audits whole projects.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnBean({
        CuratorActionRepository.class, JdbcPendingWriteRepository.class, AutoImproveGate.class,
        CuratorActionProposalSource.class
    })
    public CuratorActionScheduler curatorActionScheduler(
            CuratorActionRepository scopes,
            JdbcPendingWriteRepository pending,
            AutoImproveGate gate,
            CuratorActionProposalSource source,
            AutoImproveProperties props) {
        return new CuratorActionScheduler(scopes, pending, gate, source, props);
    }
}
