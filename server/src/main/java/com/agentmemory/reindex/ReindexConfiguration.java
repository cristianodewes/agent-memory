package com.agentmemory.reindex;

import com.agentmemory.recall.PageEmbeddingService;
import com.agentmemory.store.LinkRepository;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiGit;
import com.agentmemory.wiki.WikiPaths;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires the {@code reindex} beans (issue #14). The {@link WikilinkParser} carries no DB dependency and
 * is always available; the DB-backed pieces ({@link ReindexTxn}, {@link ReindexService}) are
 * registered only when the store repositories exist ({@link ConditionalOnBean} on
 * {@link PageRepository}/{@link LinkRepository}, which {@code StoreConfiguration} gates on a
 * {@code DataSource}) and a {@link WikiGit} is present — so the DB-less smoke test still loads cleanly,
 * while production and the Testcontainers tests get a fully wired {@link ReindexService}.
 *
 * <p><strong>Re-embed hook.</strong> When the #16 {@link PageEmbeddingService} bean is present (a
 * {@code DataSource} is configured), the {@code --reembed} step is bound to it via
 * {@link PageEmbeddingReindexHook} so reindex actually backfills {@code page_embeddings}; otherwise it
 * falls back to {@link ReindexEmbeddingHook.Noop} and a re-embed request is a logged no-op (DD-005).
 * The real hook is declared before the Noop so the Noop's {@link ConditionalOnMissingBean} only fires
 * when no embed seam exists.
 *
 * <p>Ordered after the {@code store}, {@code wiki} and {@code recall} auto-configurations so
 * {@link PageRepository}, {@link LinkRepository}, {@link WikiPaths}, {@link WikiGit} and
 * {@link PageEmbeddingService} are registered when the conditions are evaluated; declared in
 * {@code META-INF/spring/.../AutoConfiguration.imports}.
 */
@AutoConfiguration(afterName = {
    "com.agentmemory.store.StoreConfiguration",
    "com.agentmemory.wiki.WikiConfiguration",
    "com.agentmemory.recall.RecallConfiguration"
})
public class ReindexConfiguration {

    @Bean
    public WikilinkParser wikilinkParser() {
        return new WikilinkParser();
    }

    /**
     * The real re-embed hook, bound to the #16 embed seam. Present only when a
     * {@link PageEmbeddingService} bean exists (the embeddings axis is wired); it overrides the Noop.
     *
     * @param pageEmbeddingService the #16 embed-on-write / backfill service.
     * @return a hook delegating reindex re-embed to {@link PageEmbeddingService#embedPage}.
     */
    @Bean
    @ConditionalOnBean(PageEmbeddingService.class)
    public ReindexEmbeddingHook pageEmbeddingReindexHook(PageEmbeddingService pageEmbeddingService) {
        return new PageEmbeddingReindexHook(pageEmbeddingService);
    }

    /**
     * The fallback re-embed hook: a logged no-op (the embeddings axis is optional, DD-005). Registered
     * only when no other {@link ReindexEmbeddingHook} (i.e. the real one above) is defined.
     *
     * @return the no-op hook.
     */
    @Bean
    @ConditionalOnMissingBean(ReindexEmbeddingHook.class)
    public ReindexEmbeddingHook reindexEmbeddingHook() {
        return new ReindexEmbeddingHook.Noop();
    }

    @Bean
    @ConditionalOnBean({PageRepository.class, LinkRepository.class})
    public ReindexTxn reindexTxn(
            PageRepository pages, LinkRepository links, WikilinkParser wikilinkParser) {
        return new ReindexTxn(pages, links, wikilinkParser);
    }

    @Bean
    @ConditionalOnBean({ReindexTxn.class, WikiGit.class})
    public ReindexService reindexService(
            WikiPaths wikiPaths, WikiGit wikiGit, ReindexTxn reindexTxn,
            ReindexEmbeddingHook reindexEmbeddingHook) {
        return new DefaultReindexService(wikiPaths, wikiGit, reindexTxn, reindexEmbeddingHook);
    }
}
