package com.agentmemory.reindex;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.PageRecord;
import com.agentmemory.wiki.MarkdownDocument;
import com.agentmemory.wiki.WikiFormatException;
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
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ReindexService}: walks {@code wiki/}, parses each page's frontmatter + body via the
 * #13 {@link MarkdownDocument}, and rebuilds the derived index — {@code pages} (and its generated FTS
 * column) and the wikilink {@code links} graph (issue #14, DD-002). Capture tables are never touched.
 *
 * <p>This type owns the <em>IO</em> (walking the tree, reading + parsing files, asking git what
 * changed) and assembles the {@link ReindexReport}; the actual database mutations run in
 * {@link ReindexTxn}, a separate bean, so every transactional unit is entered through the Spring
 * proxy (avoiding the self-invocation pitfall #12 documents).
 *
 * <p><strong>Full</strong> ({@link ReindexMode#FULL}) wipes {@code links} then recreates a version of
 * every page from disk, so the result is the literal "drop the index, rebuild it equivalent" net.
 * <strong>Incremental</strong> ({@link ReindexMode#INCREMENTAL}) consults git for the files that
 * changed since a ref (the #13 {@link WikiGit}) and only re-indexes those, retiring pages whose files
 * were deleted. Both finish by resolving deferred/forward links, so the graph is identical for the
 * changed set (parity is an acceptance test).
 *
 * <p><strong>Idempotent + resumable.</strong> A page whose on-disk content already equals the current
 * latest version is skipped (no spurious new version), so replaying any run converges; a full run is
 * one transaction, so a crash leaves the prior index intact and a re-run rebuilds cleanly.
 */
public class DefaultReindexService implements ReindexService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReindexService.class);

    /** Files that live under {@code wiki/} but are not compiled pages (raw capture, #11/#19). */
    private static final String SESSION_LOG = "log.md";

    private final WikiPaths wikiPaths;
    private final WikiGit wikiGit;
    private final ReindexTxn txn;
    private final ReindexEmbeddingHook embeddingHook;

    public DefaultReindexService(
            WikiPaths wikiPaths, WikiGit wikiGit, ReindexTxn txn, ReindexEmbeddingHook embeddingHook) {
        this.wikiPaths = wikiPaths;
        this.wikiGit = wikiGit;
        this.txn = txn;
        this.embeddingHook = embeddingHook;
    }

    @Override
    public ReindexReport reindex(ReindexOptions options) {
        if (options == null) {
            throw new IllegalArgumentException("reindex options must not be null");
        }
        return switch (options.mode()) {
            case FULL -> full(options);
            case INCREMENTAL -> incremental(options);
        };
    }

    // --- full rebuild --------------------------------------------------------------------------

    private ReindexReport full(ReindexOptions options) {
        List<SkippedFile> skipped = new ArrayList<>();
        List<MarkdownDocument> docs = new ArrayList<>();
        int scanned = 0;
        for (Path file : listPageFiles()) {
            scanned++;
            parseOrSkip(file, skipped).ifPresent(docs::add);
        }

        ReindexTxn.FullResult result = txn.fullRebuild(docs);
        maybeReEmbed(options, result.indexed());

        ReindexReport report = new ReindexReport(
                ReindexMode.FULL, scanned, result.indexed().size(), 0,
                result.linksWritten(), result.linksResolved(), toReportSkips(skipped));
        log.info("reindex FULL: scanned={}, indexed={}, links={} (resolved {}), skipped={}",
                report.filesScanned(), report.pagesIndexed(), report.linksWritten(),
                report.linksResolved(), report.skipped().size());
        return report;
    }

    // --- incremental rebuild -------------------------------------------------------------------

    private ReindexReport incremental(ReindexOptions options) {
        WikiGit.ChangedFiles changes = wikiGit.changedSince(options.sinceRef());

        List<SkippedFile> skipped = new ArrayList<>();
        List<PageRecord> indexed = new ArrayList<>();
        int scanned = 0;
        int deleted = 0;
        int linksWritten = 0;

        for (Path file : changes.modified()) {
            if (!isPageFile(file)) {
                continue;
            }
            scanned++;
            if (!Files.exists(file)) {
                continue; // reported changed but now gone; the deleted set (below) handles removal
            }
            Optional<MarkdownDocument> doc = parseOrSkip(file, skipped);
            if (doc.isEmpty()) {
                continue;
            }
            Optional<ReindexTxn.OneResult> one = txn.indexOnePage(doc.get());
            if (one.isPresent()) {
                indexed.add(one.get().record());
                linksWritten += one.get().linksWritten();
            }
        }

        for (Path file : changes.deleted()) {
            if (!isPageFile(file)) {
                continue;
            }
            scanned++;
            Identity identity = identityOf(file);
            if (identity != null && txn.retirePage(identity)) {
                deleted++;
            }
        }

        int linksResolved = txn.resolveDeferred();
        maybeReEmbed(options, indexed);

        ReindexReport report = new ReindexReport(
                ReindexMode.INCREMENTAL, scanned, indexed.size(), deleted, linksWritten, linksResolved,
                toReportSkips(skipped));
        log.info("reindex INCREMENTAL since {}: scanned={}, indexed={}, deleted={}, links={} "
                        + "(resolved {}), skipped={}",
                changes.sinceRef(), report.filesScanned(), report.pagesIndexed(),
                report.pagesDeleted(), report.linksWritten(), report.linksResolved(),
                report.skipped().size());
        return report;
    }

    // --- shared helpers ------------------------------------------------------------------------

    private void maybeReEmbed(ReindexOptions options, List<PageRecord> indexed) {
        if (!options.reEmbed()) {
            return;
        }
        if (!embeddingHook.isActive()) {
            log.info("reindex: re-embed requested but no embedder configured — skipping "
                    + "(recall stays on FTS + graph; embeddings axis is optional, DD-005)");
            return;
        }
        for (PageRecord record : indexed) {
            embeddingHook.embed(record);
        }
        log.info("reindex: re-embedded {} page(s)", indexed.size());
    }

    /** List every compiled-page file under {@code wiki/}, deterministically ordered by path. */
    private List<Path> listPageFiles() {
        Path root = wikiPaths.wikiDir();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(DefaultReindexService::isPageFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk wiki dir " + root, e);
        }
    }

    /** A compiled page is a {@code *.md} file under {@code wiki/}, excluding git and the session log. */
    private static boolean isPageFile(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".md")) {
            return false;
        }
        if (name.equals(SESSION_LOG)) {
            return false; // raw capture log (#11/#19), not a compiled page
        }
        for (Path part : file) {
            if (part.toString().equals(".git")) {
                return false;
            }
        }
        return true;
    }

    private Optional<MarkdownDocument> parseOrSkip(Path file, List<SkippedFile> skipped) {
        String rel = relForReport(file);
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            skipped.add(new SkippedFile(rel, "unreadable: " + e.getMessage()));
            return Optional.empty();
        }
        try {
            MarkdownDocument doc = MarkdownDocument.parse(text);
            // The on-disk location must agree with the frontmatter identity, or two spellings of the
            // same page could diverge. The frontmatter is authoritative for the index; a mismatch is
            // a corrupt file, so surface it as a skip rather than indexing the wrong identity.
            Identity fromPath = identityOf(file);
            if (fromPath != null && !fromPath.equals(doc.identity())) {
                skipped.add(new SkippedFile(rel,
                        "frontmatter identity " + doc.identity() + " does not match file location "
                                + fromPath));
                return Optional.empty();
            }
            return Optional.of(doc);
        } catch (WikiFormatException e) {
            skipped.add(new SkippedFile(rel, "malformed page: " + e.getMessage()));
            return Optional.empty();
        }
    }

    /**
     * Recover the page identity from a file's location under {@code wiki/}, or null if it is not at
     * the expected {@code wiki/<ws>/<project>/<path>} depth or has an illegal component.
     */
    private Identity identityOf(Path file) {
        String[] components = wikiPaths.componentsOf(file);
        if (components == null) {
            return null;
        }
        try {
            return Identity.ofPage(
                    WorkspaceId.of(components[0]),
                    ProjectId.of(components[1]),
                    PagePath.of(components[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<ReindexReport.SkippedFile> toReportSkips(List<SkippedFile> skipped) {
        List<ReindexReport.SkippedFile> out = new ArrayList<>(skipped.size());
        for (SkippedFile s : skipped) {
            out.add(new ReindexReport.SkippedFile(s.path(), s.reason()));
        }
        return out;
    }

    private String relForReport(Path file) {
        Path root = wikiPaths.wikiDir();
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(root)) {
            return root.relativize(abs).toString().replace('\\', '/');
        }
        return file.getFileName().toString();
    }

    /** Internal mutable skip tuple (kept separate from the public report record while accumulating). */
    private record SkippedFile(String path, String reason) {
    }
}
