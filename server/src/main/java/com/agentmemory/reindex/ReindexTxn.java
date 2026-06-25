package com.agentmemory.reindex;

import com.agentmemory.core.Identity;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.MarkdownDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The transactional units of reindex (issue #14), isolated in their own Spring bean so each is
 * entered through the Spring proxy — calling a {@code @Transactional} method on {@code this} from
 * within {@link DefaultReindexService} would bypass the proxy and silently run with no transaction
 * (the self-invocation pitfall, the same one #12's {@code JdbcPageRepository} guards against).
 *
 * <p>{@link #fullRebuild} is one transaction over the whole index (atomic, so a failed run leaves the
 * prior index intact — resumable); the incremental units ({@link #indexOnePage}, {@link #retirePage},
 * {@link #resolveDeferred}) are each their own transaction so a long incremental run is resumable
 * file-by-file.
 *
 * <p><strong>Link graph.</strong> Link extraction + maintenance is delegated to the issue #27
 * {@link WikiLinkService} — the single authority for the {@code links} table and the canonical scoped
 * wikilink grammar ({@code [[path]]}, {@code [[project:path]]}, {@code [[workspace/project:path]]}).
 * Per page, {@link WikiLinkService#syncPageLinks(PageRecord)} rebuilds the page's outgoing links
 * (deleting the prior version's by source identity) and re-points inbound links at the new version, so
 * reindex produces exactly the same graph a normal page write does (issue #27 AC4). A full rebuild
 * wipes the graph first ({@link WikiLinkService#deleteAllLinks()}) and finishes by resolving any
 * still-deferred forward links ({@link WikiLinkService#resolveAllDeferred()}).
 */
public class ReindexTxn {

    private final PageRepository pages;
    private final WikiLinkService links;

    public ReindexTxn(PageRepository pages, WikiLinkService links) {
        this.pages = pages;
        this.links = links;
    }

    /**
     * Wipe {@code links} and recreate a version of every supplied page, then write their links and
     * resolve deferred ones — all in one transaction.
     *
     * @param docs the parsed page documents found on disk, in deterministic order.
     * @return the rebuild counts and the page records created (for an optional re-embed pass).
     */
    @org.springframework.transaction.annotation.Transactional
    public FullResult fullRebuild(List<MarkdownDocument> docs) {
        links.deleteAllLinks();

        List<PageRecord> indexed = new ArrayList<>(docs.size());
        for (MarkdownDocument doc : docs) {
            // Table was wiped, so each is the page's first/only version (create never supersedes here).
            indexed.add(pages.create(doc.identity(), doc.frontmatter().title(), doc.body()));
        }

        int linksWritten = 0;
        for (PageRecord record : indexed) {
            linksWritten += links.syncPageLinks(record);
        }
        int linksResolved = links.resolveAllDeferred();
        return new FullResult(indexed, linksWritten, linksResolved);
    }

    /**
     * Create a new version for one changed page unless its content already equals the current latest
     * (idempotent re-run). On change: create the new version and sync its links —
     * {@link WikiLinkService#syncPageLinks} replaces the prior version's outgoing links (keyed by
     * source identity) and re-points inbound links at the new version in one step.
     *
     * @return the new record + links written, or empty when unchanged (a no-op).
     */
    @org.springframework.transaction.annotation.Transactional
    public Optional<OneResult> indexOnePage(MarkdownDocument doc) {
        Identity identity = doc.identity();
        String title = doc.frontmatter().title();
        String body = doc.body();

        Optional<PageRecord> current = pages.readLatest(identity);
        if (current.isPresent()
                && current.get().page().title().equals(title)
                && current.get().page().body().equals(body)) {
            return Optional.empty();
        }
        PageRecord record = pages.create(identity, title, body);
        int linksWritten = links.syncPageLinks(record);
        return Optional.of(new OneResult(record, linksWritten));
    }

    /**
     * Retire a page whose file was deleted: supersede it with an empty tombstone version so it stops
     * matching FTS/graph, preserving history (#12 never hard-deletes versions). Syncing the tombstone
     * drops its outgoing links (empty body → none) and re-points inbound links at the tombstone.
     *
     * @return {@code true} if a page existed at this identity and was retired.
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean retirePage(Identity identity) {
        Optional<PageRecord> current = pages.readLatest(identity);
        if (current.isEmpty()) {
            return false;
        }
        PageRecord tombstone = pages.create(identity, current.get().page().title(), "");
        links.syncPageLinks(tombstone);
        return true;
    }

    /** Resolve any still-deferred links to their now-existing targets (own transaction). */
    @org.springframework.transaction.annotation.Transactional
    public int resolveDeferred() {
        return links.resolveAllDeferred();
    }

    /** Counts + records from a full rebuild. */
    public record FullResult(List<PageRecord> indexed, int linksWritten, int linksResolved) {
    }

    /** Outcome of indexing one changed page. */
    public record OneResult(PageRecord record, int linksWritten) {
    }
}
