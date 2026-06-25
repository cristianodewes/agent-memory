package com.agentmemory.timetravel;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.reindex.ReindexService;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.AtomicFileWriter;
import com.agentmemory.wiki.SelfWriteTracker;
import com.agentmemory.wiki.WikiGit;
import com.agentmemory.wiki.WikiPaths;
import com.agentmemory.wiki.WikiWriter;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the {@code timetravel} beans (issue #34): git-based time-travel ({@link TimeTravelService}),
 * online DB backup/restore ({@link BackupService}) and project bootstrap ({@link BootstrapService}).
 *
 * <p>Gating mirrors the other feature modules so the DB-less smoke context still loads:
 * <ul>
 *   <li>{@link TimeTravelService} needs the wiki write primitives <em>and</em> a {@link ReindexService}
 *       (which {@code ReindexConfiguration} only builds when a {@code DataSource} + wiki exist), so it
 *       is gated on {@code ReindexService}.</li>
 *   <li>{@link BackupService} is DB-only, gated on a {@link DataSource} + {@link PlatformTransactionManager}.</li>
 *   <li>{@link BootstrapService} needs the required {@link LlmProvider} (injected by name, like the
 *       consolidate module) plus the page/wiki write path, gated on {@link PageRepository}.</li>
 * </ul>
 *
 * <p>Ordered after the store, wiki, recall and reindex auto-configurations so their beans are present
 * when the conditions are evaluated; declared in {@code META-INF/spring/.../AutoConfiguration.imports}.
 * The {@code TimeTravelController} is a component-scanned {@code @RestController} (injecting these via
 * {@code ObjectProvider}), not registered here — exactly like the other controllers.
 */
@AutoConfiguration(afterName = {
    "com.agentmemory.store.StoreConfiguration",
    "com.agentmemory.wiki.WikiConfiguration",
    "com.agentmemory.recall.RecallConfiguration",
    "com.agentmemory.reindex.ReindexConfiguration"
})
public class TimeTravelConfiguration {

    /**
     * Git-based checkpoints + restore-page. Present when the wiki write path and a reindex service are
     * wired (restore-page must reindex the restored page).
     */
    @Bean
    @ConditionalOnBean({WikiGit.class, WikiPaths.class, AtomicFileWriter.class,
            SelfWriteTracker.class, ReindexService.class})
    public TimeTravelService timeTravelService(
            WikiGit wikiGit, WikiPaths wikiPaths, AtomicFileWriter atomicFileWriter,
            SelfWriteTracker selfWriteTracker, ReindexService reindexService) {
        return new TimeTravelService(
                wikiGit, wikiPaths, atomicFileWriter, selfWriteTracker, reindexService);
    }

    /**
     * Online DB backup/restore (DB-only state). Gated on a {@code DataSource} so the DB-less context
     * does not build it.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    public BackupService backupService(
            JdbcTemplate jdbcTemplate, PlatformTransactionManager txManager, AgentMemoryConfig config) {
        return new DefaultBackupService(jdbcTemplate, txManager, config.dataDir());
    }

    /**
     * Project bootstrap (seed pages from repo history via one LLM pass). Needs the page repository,
     * the wiki writer and the required LLM provider.
     */
    @Bean
    @ConditionalOnBean({PageRepository.class, WikiWriter.class})
    public BootstrapService bootstrapService(
            @Qualifier("llmProvider") LlmProvider llmProvider,
            PageRepository pageRepository, WikiWriter wikiWriter, AgentMemoryConfig config) {
        return new DefaultBootstrapService(llmProvider, pageRepository, wikiWriter, config.dataDir());
    }
}
