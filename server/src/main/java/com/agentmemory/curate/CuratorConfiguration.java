package com.agentmemory.curate;

import com.agentmemory.graph.GraphService;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiWriter;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the curator + {@code memory_lint} (issue #29).
 *
 * <p>The rule engine ({@link CuratorRepository}, {@link CuratorService}) is DB-backed and gated on a
 * single {@link DataSource} ({@link ConditionalOnSingleCandidate}); {@link CuratorService} also depends
 * on the #28 {@link GraphService} (the dangling lint) for the cross-project rule.
 *
 * <p>The lint layer adds the LLM contradiction pass ({@link ContradictionDetector}, the required
 * {@link LlmProvider} injected by name as consolidation does) and {@link MemoryLintService}, which
 * persists a {@code _lint/} page through the {@link PageRepository} + {@link WikiWriter} +
 * {@link WikiLinkService} write path. These are gated {@link ConditionalOnBean} on the beans they need,
 * so the DB-less smoke context (no store / wiki) cleanly omits them. Ordered after the store, wiki,
 * links, and graph auto-configurations so those beans exist when the conditions are evaluated.
 */
@AutoConfiguration(
        after = JdbcTemplateAutoConfiguration.class,
        afterName = {
            "com.agentmemory.store.StoreConfiguration",
            "com.agentmemory.wiki.WikiConfiguration",
            "com.agentmemory.links.LinksConfiguration",
            "com.agentmemory.graph.GraphConfiguration"
        })
public class CuratorConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public CuratorRepository curatorRepository(JdbcTemplate jdbcTemplate) {
        return new CuratorRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnBean({CuratorRepository.class, GraphService.class})
    public CuratorService curatorService(CuratorRepository curatorRepository, GraphService graphService) {
        return new CuratorService(curatorRepository, graphService);
    }

    @Bean
    @ConditionalOnBean(PageRepository.class)
    public ContradictionDetector contradictionDetector(
            @Qualifier("llmProvider") LlmProvider llmProvider) {
        return new ContradictionDetector(llmProvider);
    }

    @Bean
    @ConditionalOnBean({
        CuratorService.class, ContradictionDetector.class, PageRepository.class,
        WikiWriter.class, WikiLinkService.class
    })
    public MemoryLintService memoryLintService(
            CuratorService curatorService,
            ContradictionDetector contradictionDetector,
            PageRepository pageRepository,
            WikiWriter wikiWriter,
            WikiLinkService wikiLinkService) {
        return new MemoryLintService(
                curatorService, contradictionDetector, pageRepository, wikiWriter, wikiLinkService);
    }
}
