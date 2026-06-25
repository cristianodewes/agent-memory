package com.agentmemory.lifecycle;

import com.agentmemory.wiki.WikiPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link ResetService} (issue #33). Guards on the live-process check (invariant #9) via
 * {@link ProcessLock#detectAnyLiveHolder(Path)} — resetting while any agent-memory process (including
 * the server serving this request) holds the data dir is refused unless {@code force} is set. When
 * clear, it truncates every table (derived <em>and</em> capture — reset is the full nuke, unlike
 * reindex which spares capture) and clears the wiki tree's contents, leaving the git repo itself in
 * place with an empty tree committed.
 */
public class DefaultResetService implements ResetService {

    private static final Logger log = LoggerFactory.getLogger(DefaultResetService.class);

    /**
     * Every data table, ordered so a single TRUNCATE ... CASCADE is unnecessary; TRUNCATE of all in
     * one statement handles FKs among them. {@code flyway_schema_history} is deliberately excluded —
     * reset wipes data, not the schema.
     */
    private static final List<String> ALL_DATA_TABLES = List.of(
            "page_embeddings", "links", "pages", "observations", "sessions",
            "handoffs", "audit_log", "pending_writes", "projects", "workspaces");

    private final JdbcTemplate jdbc;
    private final WikiPaths wikiPaths;
    private final com.agentmemory.wiki.WikiGit git;
    private final Path dataDir;

    public DefaultResetService(
            JdbcTemplate jdbc, WikiPaths wikiPaths, com.agentmemory.wiki.WikiGit git, Path dataDir) {
        this.jdbc = jdbc;
        this.wikiPaths = wikiPaths;
        this.git = git;
        this.dataDir = dataDir.toAbsolutePath().normalize();
    }

    @Override
    @Transactional
    public ResetResult reset(boolean force) {
        if (!force) {
            Optional<Long> holder = ProcessLock.detectAnyLiveHolder(dataDir);
            if (holder.isPresent()) {
                log.warn("reset refused: live process pid {} holds data dir {}", holder.get(), dataDir);
                return ResetResult.refusedLiveProcess(holder.get());
            }
        }

        // TRUNCATE all data tables in one statement; RESTART IDENTITY is irrelevant (UUID PKs), CASCADE
        // covers FKs among the listed tables. flyway_schema_history is untouched (schema stays).
        String truncate = "TRUNCATE TABLE " + String.join(", ", ALL_DATA_TABLES) + " CASCADE";
        jdbc.execute(truncate);

        clearWikiTree();
        // Commit the now-empty tree so the wiki history records the reset (git repo itself is kept).
        // commitAll stages every deletion across the tree (staging the wiki root path itself yields an
        // empty JGit pattern, which is rejected).
        git.commitAll("chore(wiki): reset — wipe all project content");

        log.warn("reset performed: truncated {} tables and cleared the wiki tree (force={})",
                ALL_DATA_TABLES.size(), force);
        return ResetResult.ok(ALL_DATA_TABLES.size());
    }

    /** Delete everything under {@code wiki/} except the {@code .git} directory (the repo is kept). */
    private void clearWikiTree() {
        Path wikiDir = wikiPaths.wikiDir();
        if (!Files.isDirectory(wikiDir)) {
            return;
        }
        Path dotGit = wikiDir.resolve(".git");
        try (Stream<Path> top = Files.list(wikiDir)) {
            List<Path> entries = top.filter(p -> !p.equals(dotGit)).toList();
            for (Path entry : entries) {
                deleteRecursively(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to clear wiki tree at " + wikiDir, e);
        }
    }

    private static void deleteRecursively(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to delete " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk for delete " + path, e);
        }
    }
}
