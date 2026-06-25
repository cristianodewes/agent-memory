package com.agentmemory.reindex;

import com.agentmemory.store.LinkRepository;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiGit;
import com.agentmemory.wiki.WikiPaths;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires the {@code reindex} beans (issue #14). The {@link WikilinkParser} and the default
 * {@link ReindexEmbeddingHook} carry no DB dependency and are always available; the DB-backed pieces
 * ({@link ReindexTxn}, {@link ReindexService}) are registered only when the store repositories exist
 * ({@link ConditionalOnBean} on {@link PageRepository}/{@link LinkRepository}, which
 * {@code StoreConfiguration} gates on a {@code DataSource}) and a {@link WikiGit} is present — so the
 * DB-less smoke test still loads cleanly, while production and the Testcontainers tests get a fully
 * wired {@link ReindexService}.
 *
 * <p>Ordered after the {@code store} and {@code wiki} auto-configurations so {@link PageRepository},
 * {@link LinkRepository}, {@link WikiPaths} and {@link WikiGit} are registered when the conditions are
 * evaluated; declared in {@code META-INF/spring/.../AutoConfiguration.imports}.
 */
@AutoConfiguration(afterName = {
    "com.agentmemory.store.StoreConfiguration",
    "com.agentmemory.wiki.WikiConfiguration"
})
public class ReindexConfiguration {

    @Bean
    public WikilinkParser wikilinkParser() {
        return new WikilinkParser();
    }

    /**
     * The default re-embed hook: a logged no-op (the embeddings axis is optional, DD-005). #16/#6 can
     * publish a real {@link ReindexEmbeddingHook} bean to override this without touching reindex.
     *
     * @return the no-op hook unless another {@link ReindexEmbeddingHook} is already defined.
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
