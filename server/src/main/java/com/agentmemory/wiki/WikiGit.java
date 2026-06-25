package com.agentmemory.wiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Commit-on-write over a single git repository covering the whole {@code wiki/} tree (issue #13,
 * DD-002; ARCHITECTURE §2.2 "committed to git on every consolidation/session-end"). One repo for
 * the entire wiki keeps history coherent and {@code git}-time-travelable (#34 builds on this).
 *
 * <p>The repo is opened if present and initialized otherwise (idempotent), with an empty initial
 * commit so the working tree is never on an unborn branch. {@link #stageAndCommit} stages the given
 * paths (relative to the wiki root) and creates exactly one commit; if nothing actually changed it
 * returns empty rather than creating an empty commit, so a no-op write does not pollute history.
 *
 * <p>This type owns the JGit {@link Git} handle for the process lifetime and is closed on shutdown.
 * It performs no atomic-file logic itself — {@link AtomicFileWriter} writes the file, this stages and
 * commits it (the two are sequenced by {@link WikiWriter}).
 */
public final class WikiGit implements AutoCloseable {

    private final Path wikiDir;
    private final Git git;
    private final PersonIdent author;

    public WikiGit(Path wikiDir, String authorName, String authorEmail) {
        this.wikiDir = wikiDir.toAbsolutePath().normalize();
        this.author = new PersonIdent(authorName, authorEmail);
        try {
            this.git = openOrInit(this.wikiDir);
        } catch (IOException | GitAPIException e) {
            throw new WikiGitException("could not open/init wiki git repo at " + wikiDir, e);
        }
    }

    private static Git openOrInit(Path wikiDir) throws IOException, GitAPIException {
        Files.createDirectories(wikiDir);
        Path dotGit = wikiDir.resolve(".git");
        if (Files.isDirectory(dotGit)) {
            Repository repo = new FileRepositoryBuilder().setGitDir(dotGit.toFile()).build();
            return new Git(repo);
        }
        Git git = Git.init().setDirectory(wikiDir.toFile()).setInitialBranch("main").call();
        // Seed an empty root commit so HEAD exists (no unborn-branch edge cases for callers/#34).
        git.commit()
                .setAllowEmpty(true)
                .setMessage("chore(wiki): initialize wiki repository")
                .setAuthor(new PersonIdent("agent-memory", "agent-memory@localhost"))
                .setSign(false)
                .call();
        return git;
    }

    /**
     * Stage the given files and create one commit. Paths are made relative to the wiki root for
     * JGit's pattern matcher. Returns the new commit, or empty if the working tree had no change to
     * commit (so repeated identical writes do not create empty commits).
     *
     * @param message the commit message.
     * @param files   absolute paths under the wiki root to stage (added or modified).
     * @return the created commit, or empty when there was nothing to commit.
     */
    public synchronized Optional<RevCommit> stageAndCommit(String message, Iterable<Path> files) {
        try {
            var add = git.add();
            boolean any = false;
            for (Path f : files) {
                add.addFilepattern(relativePattern(f));
                any = true;
            }
            if (any) {
                add.call();
            }
            // Also stage deletions/renames of already-tracked files matched by the same patterns.
            var addUpdate = git.add().setUpdate(true);
            for (Path f : files) {
                addUpdate.addFilepattern(relativePattern(f));
            }
            if (any) {
                addUpdate.call();
            }
            if (git.status().call().isClean()) {
                return Optional.empty();
            }
            RevCommit commit = git.commit()
                    .setMessage(message)
                    .setAuthor(author)
                    .setCommitter(author)
                    .setSign(false)
                    .call();
            return Optional.of(commit);
        } catch (GitAPIException e) {
            throw new WikiGitException("git commit-on-write failed: " + message, e);
        }
    }

    /** @return the underlying JGit handle (for tests / advanced callers). */
    public Git git() {
        return git;
    }

    private String relativePattern(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path rel = wikiDir.relativize(abs);
        // JGit expects forward-slash patterns on all platforms.
        return rel.toString().replace('\\', '/');
    }

    @Override
    public void close() {
        git.close();
    }
}
