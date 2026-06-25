package com.agentmemory.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiPaths;
import com.agentmemory.wiki.WikiWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for issue #33's {@code reset} live-process guard (invariant #9). The data-dir
 * {@link ProcessLock} is <em>enabled</em> here (overriding the suite-wide test default that turns it
 * off), so the running test JVM stamps itself as a live holder of this isolated {@code @TempDir} data
 * dir. {@code reset(force=false)} must then refuse; {@code reset(force=true)} must wipe.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false",
    "agent-memory.lifecycle.process-lock-enabled=true"
})
@Testcontainers
class ResetIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    @Autowired PageRepository pages;
    @Autowired WikiWriter wikiWriter;
    @Autowired WikiPaths wikiPaths;
    @Autowired ResetService reset;
    @Autowired ProcessLock processLock; // present because the lock is enabled here
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static Identity pageAt(String project, String path) {
        return Identity.ofPage(
                WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", "")),
                ProjectId.of(project), PagePath.of(path));
    }

    @Test
    void theProcessLockBeanStampedThePidFile() {
        // Confirms the startup lock acquired (a live holder exists for this data dir).
        assertThat(Files.exists(processLock.pidFile())).isTrue();
        assertThat(ProcessLock.detectAnyLiveHolder(dataDir)).isPresent();
    }

    @Test
    void resetRefusesWhileALiveProcessHoldsTheDataDir() {
        Identity p = pageAt("proj", "concepts/x.md");
        pages.create(p, "X", "body\n", wikiWriter.callbackFor("seed"));
        long before = countPages();
        assertThat(before).isGreaterThan(0);

        ResetResult result = reset.reset(false);

        assertThat(result.performed()).isFalse();
        assertThat(result.reason()).contains("live");
        assertThat(result.liveHolderPid()).isGreaterThan(0);
        // Nothing was wiped.
        assertThat(countPages()).isEqualTo(before);
    }

    @Test
    void resetWithForceWipesEverythingDespiteTheLiveHolder() {
        Identity p = pageAt("proj2", "concepts/y.md");
        pages.create(p, "Y", "body\n", wikiWriter.callbackFor("seed"));
        Path projectDir = wikiPaths.projectDir(p);
        assertThat(Files.isDirectory(projectDir)).isTrue();
        assertThat(countPages()).isGreaterThan(0);

        ResetResult result = reset.reset(true);

        assertThat(result.performed()).isTrue();
        assertThat(result.tablesCleared()).isGreaterThan(0);

        // All data tables empty.
        assertThat(countPages()).isZero();
        assertThat(countRows("workspaces")).isZero();
        assertThat(countRows("projects")).isZero();
        assertThat(countRows("audit_log")).isZero();

        // Wiki content cleared, but the git repo (.git) survives.
        assertThat(Files.exists(projectDir)).isFalse();
        assertThat(Files.isDirectory(wikiPaths.wikiDir().resolve(".git"))).isTrue();
    }

    private long countPages() {
        return countRows("pages");
    }

    private long countRows(String table) {
        Long n = jdbc().queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }
}
