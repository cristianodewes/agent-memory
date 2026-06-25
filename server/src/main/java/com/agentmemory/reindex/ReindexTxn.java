package com.agentmemory.reindex;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Link;
import com.agentmemory.core.LinkId;
import com.agentmemory.store.LinkRepository;
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
 */
public class ReindexTxn {

    private final PageRepository pages;
    private final LinkRepository links;
    private final WikilinkParser wikilinkParser;

    public ReindexTxn(PageRepository pages, LinkRepository links, WikilinkParser wikilinkParser) {
        this.pages = pages;
        this.links = links;
        this.wikilinkParser = wikilinkParser;
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
        links.truncateAll();

        List<PageRecord> indexed = new ArrayList<>(docs.size());
        for (MarkdownDocument doc : docs) {
            // Table was wiped, so each is the page's first/only version (create never supersedes here).
            indexed.add(pages.create(doc.identity(), doc.frontmatter().title(), doc.body()));
        }

        int linksWritten = 0;
        for (PageRecord record : indexed) {
            linksWritten += writeLinks(record);
        }
        int linksResolved = links.resolveDeferred();
        return new FullResult(indexed, linksWritten, linksResolved);
    }

    /**
     * Create a new version for one changed page unless its content already equals the current latest
     * (idempotent re-run). On change: drop the prior version's links, create the new version, write
     * its links, and re-point inbound links at the new version.
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
        current.ifPresent(prev -> links.deleteLinksFrom(prev.id()));
        PageRecord record = pages.create(identity, title, body);
        int linksWritten = writeLinks(record);
        links.reresolveTarget(identity); // inbound links follow the page to its new version
        return Optional.of(new OneResult(record, linksWritten));
    }

    /**
     * Retire a page whose file was deleted: drop its links and supersede it with an empty tombstone
     * version so it stops matching FTS/graph, preserving history (#12 never hard-deletes versions).
     *
     * @return {@code true} if a page existed at this identity and was retired.
     */
    @org.springframework.transaction.annotation.Transactional
    public boolean retirePage(Identity identity) {
        Optional<PageRecord> current = pages.readLatest(identity);
        if (current.isEmpty()) {
            return false;
        }
        links.deleteLinksFrom(current.get().id());
        pages.create(identity, current.get().page().title(), "");
        links.reresolveTarget(identity);
        return true;
    }

    /** Resolve any still-deferred links to their now-existing targets (own transaction). */
    @org.springframework.transaction.annotation.Transactional
    public int resolveDeferred() {
        return links.resolveDeferred();
    }

    private int writeLinks(PageRecord record) {
        Identity source = record.identity();
        List<WikilinkRef> refs = wikilinkParser.parse(source, record.page().body());
        for (WikilinkRef ref : refs) {
            links.insert(record.id(), new Link(LinkId.newId(), source, ref.target(), ref.anchor(), false));
        }
        return refs.size();
    }

    /** Counts + records from a full rebuild. */
    public record FullResult(List<PageRecord> indexed, int linksWritten, int linksResolved) {
    }

    /** Outcome of indexing one changed page. */
    public record OneResult(PageRecord record, int linksWritten) {
    }
}
