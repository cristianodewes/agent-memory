package com.agentmemory.consolidate;

import com.agentmemory.hooks.IngestService;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.mcp.McpReadRepository;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiWriter;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code consolidate} beans (issue #18). Session synthesis needs the capture log (a JDBC
 * {@link DataSource}), the {@link PageRepository} + {@link WikiWriter} to write the page (#12/#13),
 * and the required {@link LlmProvider} (#6). The DB-backed beans are therefore gated on a
 * {@code DataSource} ({@link ConditionalOnSingleCandidate}, the same gate {@code StoreConfiguration}
 * and {@code RecallConfiguration} use) so the DB-less smoke test still loads; ordered after the store
 * and wiki auto-configurations so their beans are present when the conditions are evaluated.
 *
 * <p>Every {@link LlmProvider} consumer here binds the primary by name
 * ({@code @Qualifier("llmProvider")}). Since issue #146 added the optional {@code recallLlmProvider}
 * (a second {@link LlmProvider} bean carrying the cheap recall steps), a bare by-type injection has two
 * candidates; consolidation is heavyweight work that wants the primary chat/consolidation provider, so
 * each site qualifies explicitly (the primary is also {@code @Primary}, so this is belt-and-suspenders
 * — and it mirrors {@code LlmModule}'s own consumers).
 */
@AutoConfiguration(afterName = {
    "com.agentmemory.store.StoreConfiguration",
    "com.agentmemory.links.LinksConfiguration",
    "com.agentmemory.wiki.WikiConfiguration",
    "com.agentmemory.mcp.McpConfiguration"
})
public class ConsolidateConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public SessionObservationReader sessionObservationReader(JdbcTemplate jdbcTemplate) {
        return new JdbcSessionObservationReader(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean({
        SessionObservationReader.class, PageRepository.class, WikiWriter.class, WikiLinkService.class
    })
    public SessionSynthesizer sessionSynthesizer(
            SessionObservationReader reader,
            @Qualifier("llmProvider") LlmProvider llmProvider,
            PageRepository pageRepository,
            WikiWriter wikiWriter,
            WikiLinkService wikiLinkService) {
        return new SessionSynthesizer(
                reader, llmProvider, pageRepository, wikiWriter, wikiLinkService);
    }

    @Bean
    @ConditionalOnBean(SessionSynthesizer.class)
    public SessionConsolidationTrigger sessionConsolidationTrigger(SessionSynthesizer synthesizer) {
        return new SessionConsolidationTrigger(synthesizer);
    }

    /**
     * The LLM multi-page consolidation orchestrator (issue #19) behind {@code memory_consolidate}.
     * Reuses the {@link MemoryWriteService} admission chain for the atomic fan-out (rows + files + one
     * commit) and {@link McpReadRepository} to show the model existing durable pages as update context.
     * Gated on those beans being present (a wired store + MCP layer).
     */
    @Bean
    @ConditionalOnBean({SessionObservationReader.class, MemoryWriteService.class, McpReadRepository.class})
    public Consolidator consolidator(
            SessionObservationReader reader,
            @Qualifier("llmProvider") LlmProvider llmProvider,
            MemoryWriteService memoryWriteService,
            McpReadRepository mcpReadRepository) {
        return new Consolidator(reader, llmProvider, memoryWriteService, mcpReadRepository);
    }

    /**
     * The {@code memory_explore} prose-digest service (issue #19): the briefing snapshot plus one LLM
     * call, calibrated to staleness. Gated on the read repository + page repository being present.
     */
    @Bean
    @ConditionalOnBean({McpReadRepository.class, PageRepository.class})
    public MemoryExplore memoryExplore(
            @Qualifier("llmProvider") LlmProvider llmProvider,
            McpReadRepository mcpReadRepository,
            PageRepository pageRepository) {
        return new MemoryExplore(llmProvider, mcpReadRepository, pageRepository);
    }

    /**
     * The listener that forwards captured observations to the consolidation trigger, dispatching the
     * actual (blocking, LLM-backed) synthesis off the ingest worker thread (invariant #5). Published as
     * a managed bean — and {@code destroyMethod = "close"} so its dedicated synthesis executor is shut
     * down with the context. Gated on the trigger (declared above), so it exists exactly when synthesis
     * is fully wired (DataSource + LLM). It is attached to ingest by
     * {@link #consolidationTriggerRegistration} below.
     *
     * @param trigger the consolidation trigger to forward observations to.
     * @return the post-write listener.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnBean(SessionConsolidationTrigger.class)
    public ConsolidationObservationListener consolidationObservationListener(
            SessionConsolidationTrigger trigger) {
        return new ConsolidationObservationListener(trigger);
    }

    /**
     * Attach the consolidation listener to the ingest pipeline once both exist, via
     * {@code IngestService.addPostWriteListener} — the same additive fan-out the handoff trigger uses
     * (#22), so consolidation coexists with handoff rather than overwriting a single slot. Without this
     * registration the trigger is a seam nothing invokes and session-end synthesis never runs. Mirrors
     * {@code HandoffConfiguration.handoffSessionEndRegistration}: {@link IngestService} is resolved
     * through an {@link ObjectProvider} so this is a no-op when ingest is not wired (DB-less context),
     * and the wiring is performed in the bean's construction. Gated on the listener (declared above).
     *
     * @param ingest   the ingest service (optional).
     * @param listener the consolidation post-write listener.
     * @return a marker object recording whether the listener was attached to ingest.
     */
    @Bean
    @ConditionalOnBean(ConsolidationObservationListener.class)
    public ConsolidationTriggerRegistration consolidationTriggerRegistration(
            ObjectProvider<IngestService> ingest, ConsolidationObservationListener listener) {
        IngestService service = ingest.getIfAvailable();
        if (service != null) {
            service.addPostWriteListener(listener);
        }
        return new ConsolidationTriggerRegistration(service != null);
    }

    /** Marker bean recording whether the consolidation listener was attached to ingest. */
    public record ConsolidationTriggerRegistration(boolean attached) {}
}
