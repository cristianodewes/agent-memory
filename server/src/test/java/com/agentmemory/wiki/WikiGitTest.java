package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Commit-on-write behavior over the single wiki repo. */
class WikiGitTest {

    @Test
    void initCreatesRepoWithRootCommit(@TempDir Path wiki) throws Exception {
        try (WikiGit git = new WikiGit(wiki, "tester", "tester@localhost")) {
            assertThat(Files.isDirectory(wiki.resolve(".git"))).isTrue();
            try (Git jgit = git.git()) {
                List<RevCommit> log = toList(jgit);
                assertThat(log).hasSize(1);
                assertThat(log.get(0).getFullMessage()).contains("initialize");
            }
        }
    }

    @Test
    void stageAndCommitCreatesExactlyOneCommitPerWrite(@TempDir Path wiki) throws Exception {
        try (WikiGit git = new WikiGit(wiki, "tester", "tester@localhost")) {
            Path file = wiki.resolve("concepts/recall.md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "v1\n");

            var c1 = git.stageAndCommit("consolidate: concepts/recall.md", List.of(file));
            assertThat(c1).isPresent();
            assertThat(c1.get().getFullMessage()).isEqualTo("consolidate: concepts/recall.md");

            Files.writeString(file, "v2\n");
            var c2 = git.stageAndCommit("consolidate: concepts/recall.md (v2)", List.of(file));
            assertThat(c2).isPresent();
            assertThat(c2.get().getName()).isNotEqualTo(c1.get().getName());

            // init + 2 writes = 3 commits.
            assertThat(commitCount(wiki)).isEqualTo(3);
        }
    }

    @Test
    void noOpCommitReturnsEmptyAndDoesNotPolluteHistory(@TempDir Path wiki) throws Exception {
        try (WikiGit git = new WikiGit(wiki, "tester", "tester@localhost")) {
            Path file = wiki.resolve("a.md");
            Files.writeString(file, "same\n");
            assertThat(git.stageAndCommit("first", List.of(file))).isPresent();

            // No content change before the second commit attempt.
            var second = git.stageAndCommit("noop", List.of(file));
            assertThat(second).isEmpty();
            assertThat(commitCount(wiki)).isEqualTo(2); // init + first only
        }
    }

    private static int commitCount(Path wiki) throws Exception {
        try (WikiGit git = new WikiGit(wiki, "x", "x@localhost"); Git jgit = git.git()) {
            return toList(jgit).size();
        }
    }

    private static List<RevCommit> toList(Git jgit) throws IOException {
        try {
            var iter = jgit.log().call();
            var out = new java.util.ArrayList<RevCommit>();
            iter.forEach(out::add);
            return out;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
