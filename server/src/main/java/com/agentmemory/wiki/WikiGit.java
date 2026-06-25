package com.agentmemory.wiki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

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

    /**
     * The page files that changed between a git revision and the current working tree — the input to
     * an incremental reindex (#14). Compares {@code sinceRef}'s tree against the working tree (so both
     * committed-since-ref and uncommitted edits count), returning absolute paths under the wiki root.
     *
     * <p>{@code sinceRef} null/blank means "since the previous commit" ({@code HEAD~1}). When the repo
     * has no previous commit (only the seed root commit), or {@code sinceRef} cannot be resolved, the
     * range is taken from the <em>empty tree</em> — i.e. every currently-tracked-or-present file is
     * reported as modified, which makes a first incremental run behave like a full scan of the tree.
     *
     * @param sinceRef a git revision (sha, {@code HEAD~1}, tag, …), or null/blank for {@code HEAD~1}.
     * @return the modified/added and deleted page files, with the ref actually used.
     */
    public synchronized ChangedFiles changedSince(String sinceRef) {
        String requested = (sinceRef == null || sinceRef.isBlank()) ? "HEAD~1" : sinceRef.trim();
        try {
            ObjectId oldId = git.getRepository().resolve(requested + "^{tree}");
            AbstractTreeIterator oldTree;
            String usedRef;
            if (oldId == null) {
                oldTree = new org.eclipse.jgit.treewalk.EmptyTreeIterator();
                usedRef = "(empty-tree)";
            } else {
                oldTree = treeIteratorFor(oldId);
                usedRef = requested;
            }

            List<DiffEntry> diffs = git.diff()
                    .setOldTree(oldTree)
                    .setNewTree(new FileTreeIterator(git.getRepository()))
                    .setShowNameAndStatusOnly(true)
                    .call();

            List<Path> modified = new ArrayList<>();
            List<Path> deleted = new ArrayList<>();
            for (DiffEntry d : diffs) {
                switch (d.getChangeType()) {
                    case ADD, MODIFY, COPY -> modified.add(wikiDir.resolve(d.getNewPath()));
                    case DELETE -> deleted.add(wikiDir.resolve(d.getOldPath()));
                    case RENAME -> {
                        deleted.add(wikiDir.resolve(d.getOldPath()));
                        modified.add(wikiDir.resolve(d.getNewPath()));
                    }
                    default -> { /* no other change types from name/status diff */ }
                }
            }
            return new ChangedFiles(usedRef, List.copyOf(modified), List.copyOf(deleted));
        } catch (IOException | GitAPIException e) {
            throw new WikiGitException("could not compute changed files since " + requested, e);
        }
    }

    private CanonicalTreeParser treeIteratorFor(ObjectId treeId) throws IOException {
        try (RevWalk walk = new RevWalk(git.getRepository());
                var reader = git.getRepository().newObjectReader()) {
            // treeId already resolves to a tree (we asked for ^{tree}); parse it directly.
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, treeId);
            return parser;
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

    /**
     * Result of {@link #changedSince}: the wiki files that changed in a revision range.
     *
     * @param sinceRef the ref actually diffed from ({@code "(empty-tree)"} when none resolved).
     * @param modified absolute paths of added/modified (and rename-target) files under the wiki root.
     * @param deleted  absolute paths of deleted (and rename-source) files under the wiki root.
     */
    public record ChangedFiles(String sinceRef, List<Path> modified, List<Path> deleted) {
        public ChangedFiles {
            modified = modified == null ? List.of() : List.copyOf(modified);
            deleted = deleted == null ? List.of() : List.copyOf(deleted);
        }
    }
}
