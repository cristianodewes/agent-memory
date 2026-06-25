package com.agentmemory.lifecycle;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.store.AuditWriter;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiGit;
import com.agentmemory.wiki.WikiPaths;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@code lifecycle} beans (issue #33): the project rename/move/purge service, the guarded
 * {@code reset} service, the wiki-subtree filesystem ops, and the data-dir {@link ProcessLock}.
 *
 * <p>The DB-backed services are registered only when the store repositories and a {@link WikiGit}
 * exist ({@link ConditionalOnBean}, which {@code StoreConfiguration}/{@code WikiConfiguration} gate on
 * a {@code DataSource}) — so the DB-less smoke context still loads. The {@link ProcessLock} is acquired
 * at startup so a second server cannot share the data dir and {@code reset} can detect a live holder
 * (invariant #9); it can be disabled with {@code agent-memory.lifecycle.process-lock-enabled=false}
 * (e.g. for tooling that deliberately runs alongside).
 *
 * <p>Ordered after the {@code store} and {@code wiki} auto-configurations so the repositories,
 * {@link WikiPaths} and {@link WikiGit} are present when the conditions are evaluated; declared in
 * {@code META-INF/spring/.../AutoConfiguration.imports}.
 */
@AutoConfiguration(afterName = {
    "com.agentmemory.store.StoreConfiguration",
    "com.agentmemory.links.LinksConfiguration",
    "com.agentmemory.wiki.WikiConfiguration"
})
public class LifecycleConfiguration {

    /**
     * The data-dir liveness lock (invariant #9). Acquired on context start (a second server fails
     * fast) and released on shutdown. Present unless explicitly disabled.
     *
     * @param config the resolved configuration (for the data dir).
     * @return the process lock guarding this data dir.
     */
    @Bean(initMethod = "acquire", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "agent-memory.lifecycle", name = "process-lock-enabled",
            havingValue = "true", matchIfMissing = true)
    public ProcessLock processLock(AgentMemoryConfig config) {
        return new ProcessLock(config.dataDir());
    }

    /**
     * The wiki-subtree move/delete ops, committing to git. Present when a {@link WikiGit} is wired.
     *
     * @param wikiGit the wiki git handle.
     * @return the filesystem/git subtree ops.
     */
    @Bean
    @ConditionalOnBean(WikiGit.class)
    public JdbcProjectLifecycleService.WikiDirOps wikiDirOps(WikiGit wikiGit) {
        return new JdbcProjectLifecycleService.GitWikiDirOps(wikiGit);
    }

    /**
     * The project rename/move/purge service. Needs the store repositories, the audit writer, the wiki
     * paths and the wiki dir ops.
     */
    @Bean
    @ConditionalOnBean({PageRepository.class, WikiLinkService.class, AuditWriter.class,
            WikiPaths.class, JdbcProjectLifecycleService.WikiDirOps.class})
    public ProjectLifecycleService projectLifecycleService(
            JdbcTemplate jdbcTemplate, WikiLinkService links, AuditWriter audit,
            WikiPaths wikiPaths, JdbcProjectLifecycleService.WikiDirOps wikiDirOps) {
        return new JdbcProjectLifecycleService(jdbcTemplate, links, audit, wikiPaths, wikiDirOps);
    }

    /**
     * The guarded {@code reset} service. Needs the JDBC template, the wiki paths/git, and the data dir
     * (for the live-process check).
     */
    @Bean
    @ConditionalOnBean({PageRepository.class, WikiGit.class})
    public ResetService resetService(
            JdbcTemplate jdbcTemplate, WikiPaths wikiPaths, WikiGit wikiGit, AgentMemoryConfig config) {
        return new DefaultResetService(jdbcTemplate, wikiPaths, wikiGit, config.dataDir());
    }
}
