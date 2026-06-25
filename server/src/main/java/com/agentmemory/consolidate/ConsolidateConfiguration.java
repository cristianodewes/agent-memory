package com.agentmemory.consolidate;

import com.agentmemory.links.WikiLinkService;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.mcp.McpReadRepository;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiWriter;
import javax.sql.DataSource;
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
 * <p>The {@link LlmProvider} is injected by name ({@code @Qualifier("llmProvider")}): the
 * deterministic {@code test} double registers under both the {@code llmProvider} and {@code embedder}
 * bean names, so a by-type {@link LlmProvider} injection is unambiguous in production but the explicit
 * qualifier keeps it robust and mirrors {@code LlmModule}'s own consumers.
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
            LlmProvider llmProvider,
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
            LlmProvider llmProvider,
            McpReadRepository mcpReadRepository,
            PageRepository pageRepository) {
        return new MemoryExplore(llmProvider, mcpReadRepository, pageRepository);
    }
}
