package com.agentmemory.wiki;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.store.PageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Wires the {@code wiki} beans (issue #13): the atomic file writer, the self-write tracker, the
 * single git repo (commit-on-write), the high-level {@link WikiWriter}, and the external-edit
 * {@link WikiFileWatcher}.
 *
 * <p>The writer side (paths, file writer, git, {@link WikiWriter}) needs only the resolved data dir,
 * so it is always available. The <em>watcher</em> reconciles external edits into the Postgres index,
 * so it is registered only when a {@link PageRepository} bean exists ({@link ConditionalOnBean}),
 * mirroring how {@code StoreConfiguration} gates its repository on a {@code DataSource} — this keeps
 * the DB-less smoke test loading. It can also be turned off with
 * {@code agent-memory.wiki.watch-enabled=false} (default on).
 *
 * <p>Ordered after {@code StoreConfiguration} (via the {@code store} package's auto-configuration)
 * so the {@link PageRepository} bean is present when {@link ConditionalOnBean} is evaluated;
 * declared in {@code META-INF/spring/.../AutoConfiguration.imports}.
 */
@AutoConfiguration(afterName = "com.agentmemory.store.StoreConfiguration")
public class WikiConfiguration {

    @Bean
    public WikiPaths wikiPaths(AgentMemoryConfig config) {
        return new WikiPaths(config.wikiDir());
    }

    @Bean
    public AtomicFileWriter atomicFileWriter() {
        return new AtomicFileWriter();
    }

    @Bean
    public SelfWriteTracker selfWriteTracker() {
        return new SelfWriteTracker();
    }

    @Bean(destroyMethod = "close")
    public WikiGit wikiGit(
            WikiPaths wikiPaths,
            @Value("${agent-memory.wiki.git.author-name:agent-memory}") String authorName,
            @Value("${agent-memory.wiki.git.author-email:agent-memory@localhost}") String authorEmail) {
        return new WikiGit(wikiPaths.wikiDir(), authorName, authorEmail);
    }

    @Bean
    public WikiWriter wikiWriter(
            WikiPaths wikiPaths, AtomicFileWriter fileWriter,
            SelfWriteTracker selfWrites, WikiGit git) {
        return new WikiWriter(wikiPaths, fileWriter, selfWrites, git);
    }

    /**
     * The external-edit watcher. Present only when there is a {@link PageRepository} to reconcile
     * into (i.e. a DataSource is configured) and not explicitly disabled. Started on context
     * refresh and closed on shutdown (its {@code close()} stops the watch thread).
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnBean(PageRepository.class)
    @ConditionalOnProperty(prefix = "agent-memory.wiki", name = "watch-enabled",
            havingValue = "true", matchIfMissing = true)
    public WikiFileWatcher wikiFileWatcher(
            WikiPaths wikiPaths, SelfWriteTracker selfWrites, PageRepository pages,
            @Value("${agent-memory.wiki.watch-debounce-millis:250}") long debounceMillis) {
        return new WikiFileWatcher(wikiPaths, selfWrites, pages, debounceMillis);
    }
}
