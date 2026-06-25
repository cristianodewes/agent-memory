package com.agentmemory.timetravel;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.reindex.ReindexOptions;
import com.agentmemory.reindex.ReindexReport;
import com.agentmemory.reindex.ReindexService;
import com.agentmemory.wiki.AtomicFileWriter;
import com.agentmemory.wiki.MarkdownDocument;
import com.agentmemory.wiki.SelfWriteTracker;
import com.agentmemory.wiki.WikiGit;
import com.agentmemory.wiki.WikiPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Git-based time-travel over the wiki (issue #34; Survey §2.10). The wiki is a single git repo
 * (DD-002, {@code WikiGit}), so two recovery capabilities fall straight out of its history:
 *
 * <ul>
 *   <li><b>{@link #recentCheckpoints(int) checkpoints}</b> — the recent wiki commits, each a point a
 *       page can be inspected at or restored from.</li>
 *   <li><b>{@link #restorePage restore-page}</b> — read a single markdown file's <em>exact</em> bytes
 *       at a chosen revision, write them back atomically (invariant #10) as a new commit, then run an
 *       incremental {@link ReindexService reindex} (#14) so the derived Postgres index reflects the
 *       restored page. Only that one page changes, so the reindex diffs the new commit against its
 *       parent and touches just it.</li>
 * </ul>
 *
 * <p>Restore preserves the historical bytes verbatim rather than re-rendering: the content is parsed
 * only to validate it is a well-formed page and to recover the {@code (workspace, project, path)}
 * identity, then the original text is written unchanged. This keeps a restore faithful to history
 * (no frontmatter reordering / timestamp normalization) while still flowing through the same
 * atomic-write + self-write-tracking + commit path every wiki write uses, so the file watcher does
 * not treat the restore as an external edit and loop.
 */
public class TimeTravelService {

    private static final Logger log = LoggerFactory.getLogger(TimeTravelService.class);

    /** Length of the abbreviated sha surfaced on a {@link Checkpoint}. */
    private static final int SHORT_SHA_LEN = 10;

    private final WikiGit wikiGit;
    private final WikiPaths wikiPaths;
    private final AtomicFileWriter fileWriter;
    private final SelfWriteTracker selfWrites;
    private final ReindexService reindexService;

    public TimeTravelService(WikiGit wikiGit, WikiPaths wikiPaths, AtomicFileWriter fileWriter,
                             SelfWriteTracker selfWrites, ReindexService reindexService) {
        this.wikiGit = wikiGit;
        this.wikiPaths = wikiPaths;
        this.fileWriter = fileWriter;
        this.selfWrites = selfWrites;
        this.reindexService = reindexService;
    }

    // --- checkpoints -----------------------------------------------------------------------------

    /**
     * The {@code limit} most-recent wiki commits, newest first.
     *
     * @param limit the maximum number of commits to return; clamped to at least 1.
     * @return the recent checkpoints (may be shorter than {@code limit} on a young repo).
     */
    public List<Checkpoint> recentCheckpoints(int limit) {
        int max = Math.max(1, limit);
        List<Checkpoint> out = new ArrayList<>();
        try {
            for (RevCommit c : wikiGit.git().log().setMaxCount(max).call()) {
                String sha = c.getName();
                out.add(new Checkpoint(
                        sha,
                        sha.length() > SHORT_SHA_LEN ? sha.substring(0, SHORT_SHA_LEN) : sha,
                        c.getFullMessage().strip(),
                        c.getAuthorIdent() != null ? c.getAuthorIdent().getName() : "unknown",
                        java.time.Instant.ofEpochSecond(c.getCommitTime())));
            }
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new TimeTravelException("could not read wiki history", e);
        }
        return List.copyOf(out);
    }

    // --- restore-page ----------------------------------------------------------------------------

    /**
     * Restore a single page's markdown file from a git revision and reindex it (issue #34). The
     * content at {@code (fromRev, workspace/project/path)} is read verbatim from git, written back
     * atomically as a new commit, and an incremental reindex rebuilds the derived index for it.
     *
     * @param workspace the page's workspace slug.
     * @param project   the page's project slug.
     * @param path      the page path under {@code wiki/<workspace>/<project>/} (e.g. {@code sessions/x.md}).
     * @param fromRev   the git revision to restore from (a sha, {@code HEAD~2}, a tag, …).
     * @return the restore outcome (the new commit, or a no-op when the file already matches the rev).
     * @throws TimeTravelException if the revision cannot be resolved or the path does not exist there.
     */
    public RestorePageResult restorePage(String workspace, String project, String path, String fromRev) {
        if (fromRev == null || fromRev.isBlank()) {
            throw new TimeTravelException("restore-page requires a 'from' revision");
        }
        Identity identity = Identity.ofPage(
                WorkspaceId.of(workspace), ProjectId.of(project), PagePath.of(path));
        Path file = wikiPaths.resolve(identity);
        String relPath = wikiRelative(file);

        String restored = readBlobAtRevision(fromRev.trim(), relPath);

        // Validate the restored bytes are a well-formed page (and that the frontmatter identity matches
        // the path we were asked to restore) — a corrupt/mismatched historical blob must not be written.
        MarkdownDocument doc = MarkdownDocument.parse(restored);
        if (!doc.identity().equals(identity)) {
            throw new TimeTravelException(
                    "restored content's frontmatter identity " + doc.identity()
                            + " does not match the requested page " + identity);
        }

        // Short-circuit a no-op restore: if the working tree already holds these exact bytes, do not
        // create an empty commit or run a reindex (idempotent re-restore).
        if (contentMatchesOnDisk(file, restored)) {
            log.debug("restore-page {} from {} is a no-op (working tree already matches)",
                    identity.page().value(), fromRev);
            return new RestorePageResult(identity, fromRev, null, 0, false);
        }

        // Atomic write + self-write tracking + commit (the same ordering every wiki write uses, so the
        // watcher recognizes this as our write and does not re-ingest it).
        String expectedHash = AtomicFileWriter.hashOf(restored);
        selfWrites.recordWrite(file, expectedHash);
        try {
            String writtenHash = fileWriter.write(file, restored);
            if (!writtenHash.equals(expectedHash)) {
                selfWrites.recordWrite(file, writtenHash);
                throw new TimeTravelException("written hash differs from restored hash for " + file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write restored page " + file, e);
        }

        String message = "restore: " + identity.page().value() + " from " + fromRev;
        Optional<RevCommit> commit = wikiGit.stageAndCommit(message, List.of(file));

        // Reindex just the restored page: the restore is the latest commit, so diffing HEAD against its
        // parent (HEAD~1) yields exactly this file. If nothing committed (shouldn't happen after a real
        // content change), there is nothing to reindex.
        int pagesIndexed = 0;
        if (commit.isPresent()) {
            ReindexReport report = reindexService.reindex(ReindexOptions.incremental("HEAD~1"));
            pagesIndexed = report.pagesIndexed();
        }
        log.info("restored {} from {} -> commit {} ({} pages reindexed)",
                identity.page().value(), fromRev,
                commit.map(RevCommit::getName).orElse("(none)"), pagesIndexed);
        return new RestorePageResult(
                identity, fromRev, commit.map(RevCommit::getName).orElse(null), pagesIndexed, true);
    }

    // --- git plumbing ----------------------------------------------------------------------------

    /** Read a path's blob content at a revision, or fail if the revision/path is not present there. */
    private String readBlobAtRevision(String rev, String relPath) {
        Repository repo = wikiGit.git().getRepository();
        ObjectId treeId;
        try {
            treeId = repo.resolve(rev + "^{tree}");
        } catch (IOException e) {
            throw new TimeTravelException("could not resolve revision '" + rev + "'", e);
        }
        if (treeId == null) {
            throw new TimeTravelException("unknown revision '" + rev + "'");
        }
        try (ObjectReader reader = repo.newObjectReader();
                TreeWalk walk = TreeWalk.forPath(reader, relPath, treeId)) {
            if (walk == null) {
                throw new TimeTravelException(
                        "path '" + relPath + "' does not exist at revision '" + rev + "'");
            }
            ObjectLoader loader = reader.open(walk.getObjectId(0));
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TimeTravelException(
                    "could not read '" + relPath + "' at revision '" + rev + "'", e);
        }
    }

    /** The wiki-root-relative, forward-slash path for an absolute wiki file (JGit's path form). */
    private String wikiRelative(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        return wikiPaths.wikiDir().relativize(abs).toString().replace('\\', '/');
    }

    private static boolean contentMatchesOnDisk(Path file, String content) {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            return Files.readString(file, StandardCharsets.UTF_8).equals(content);
        } catch (IOException e) {
            return false; // unreadable on-disk file → treat as a real restore
        }
    }
}
