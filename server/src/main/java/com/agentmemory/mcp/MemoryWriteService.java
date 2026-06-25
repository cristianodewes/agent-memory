package com.agentmemory.mcp;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Uuid7;
import com.agentmemory.hooks.Sanitizer;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.store.PageWriteCallback;
import com.agentmemory.wiki.WikiWriter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The admission chain behind the two MCP write tools (issue #20, ARCHITECTURE §5.1): the direct
 * {@code memory_write_page} / {@code memory_delete_page} path the user takes when they explicitly ask
 * to remember a durable fact or to delete a page. Both go through the <em>same</em> store + wiki
 * writer as everything else — there is no special-case bypass of versioning or the single-writer
 * discipline (invariants #2, #3, #10; DD-002, DD-006).
 *
 * <h2>memory_write_page</h2>
 * The body is first run through the privacy redaction pipeline
 * ({@link Sanitizer#redactText(String)} — secrets/keys/emails/home-dir paths stripped; invariant #6),
 * then written via {@link PageRepository#create} with a {@link PageWriteCallback} that, inside the
 * store's single write transaction, (1) renders + atomically writes the markdown file and commits it
 * to git ({@link WikiWriter}, DD-002) and (2) records the mutation in {@code audit_log}. Row, file,
 * commit and audit are therefore one atomic logical operation — and because the {@code pages}
 * full-text {@code search_vector} is a {@code GENERATED ... STORED} column populated by the INSERT,
 * the page is immediately searchable, with no post-commit indexing step. The page's wikilink graph is
 * then maintained via {@link WikiLinkService#syncPageLinks} (#27), so any {@code [[links]]} the user
 * wrote in the body become live edges — this is the write path's slice of the same link maintenance
 * reindex and consolidation perform.
 *
 * <h2>memory_delete_page</h2>
 * Delete by exact path, idempotent. In one transaction: the page's version rows are removed from the
 * index (cascading to its {@code links}/{@code page_embeddings} via the schema FKs), the markdown file
 * is removed from the wiki and the deletion committed to git (so a later reindex does not resurrect
 * it), and the mutation is audited. Deleting a path with no current page is a <strong>no-op
 * success</strong> (still audited, flagged {@code existed=false}). A failure of the wiki/git side
 * effect rolls the whole transaction back so the index and the wiki cannot drift.
 *
 * <p>Every mutation is written to {@code audit_log} with the 3-tuple identity {@code (workspace,
 * project, path)} and the {@code actor} (these tools run on behalf of the MCP client, recorded as
 * {@code "mcp"}), satisfying issue #20's "audited with the identity tuple and actor" criterion.
 */
public final class MemoryWriteService {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteService.class);

    /** Actor recorded in the audit log for mutations arriving through the MCP tool surface. */
    public static final String ACTOR_MCP = "mcp";

    private final PageRepository pages;
    private final WikiWriter wikiWriter;
    private final WikiLinkService links;
    private final Sanitizer sanitizer;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MemoryWriteService(
            PageRepository pages,
            WikiWriter wikiWriter,
            WikiLinkService links,
            Sanitizer sanitizer,
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager) {
        this.pages = pages;
        this.wikiWriter = wikiWriter;
        this.links = links;
        this.sanitizer = sanitizer;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Create a new version of the page at {@code identity} with the given title and body, through the
     * version chain + wiki writer + commit, recording an audit entry — all atomically. The body is
     * redacted first (invariant #6). The returned page is immediately searchable.
     *
     * @param identity page-scoped identity (path required); never null.
     * @param title    the page title; never null/blank.
     * @param body     the markdown body to remember; never null (may be empty).
     * @param actor    who is performing the write (e.g. {@link #ACTOR_MCP}).
     * @return the freshly stored latest version.
     */
    public PageRecord writePage(Identity identity, String title, String body, String actor) {
        requirePageScoped(identity);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("page title must not be blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("page body must not be null");
        }
        String redacted = sanitizer.redactText(body);
        String commitMessage = "write: " + identity.page().value();

        // The callback runs inside PageRepository.create's @Transactional unit, after the row is
        // written and before commit: write the wiki file + commit, then the audit row. A throw here
        // rolls the page row back too (create wraps it in PageWriteException), keeping row+file+audit
        // in lockstep.
        PageWriteCallback callback = persisted -> {
            wikiWriter.callbackFor(commitMessage).afterPageWritten(persisted);
            writeAudit("page.write", persisted.identity(), persisted.id().value(), actor,
                    "{\"existed\":" + (persisted.page().supersedes() != null) + "}");
        };
        PageRecord persisted = pages.create(identity, title, redacted, callback);
        // Maintain the wikilink graph for the page just written (#27): record its outgoing [[links]]
        // and re-point any inbound links now that this page (version) exists. Same seam reindex and
        // consolidation use, so memory_write_page is not a dormant write path for links.
        links.syncPageLinks(persisted);
        log.debug("memory_write_page {} by {} -> {}",
                identity.page().value(), actor, persisted.id().value());
        return persisted;
    }

    /**
     * Atomically create a new version of <em>several</em> pages in one operation — the multi-page
     * fan-out behind consolidation (issue #19). All page rows are inserted, all wiki files written, and
     * the whole set committed as <strong>one git commit</strong>, inside a single transaction: if any
     * page fails (a bad body, a wiki/git error), the entire fan-out rolls back — no partial set of rows
     * and no partial commit (the "all-or-nothing / one commit" acceptance criterion). Each body is
     * redacted first (invariant #6); each mutation is audited; the wikilink graph for every written page
     * is then maintained so forward links between the new pages resolve (#27).
     *
     * <p>Ordering inside the transaction: insert every page row (each takes its own per-path advisory
     * lock; distinct paths proceed independently), <em>then</em> write + commit all files once. Writing
     * files only after all rows are inserted means a row-level failure (e.g. a constraint) never leaves
     * a written file behind, and the single commit covers exactly the rows that were inserted. Links are
     * synced after the transaction commits, mirroring {@link #writePage}.
     *
     * @param writes the pages to create; never null/empty. Duplicate paths in one call are rejected.
     * @param actor  who is performing the write (e.g. {@link #ACTOR_MCP}).
     * @return the freshly stored latest version of each page, in input order.
     */
    public List<PageRecord> writePages(List<PageWrite> writes, String actor) {
        if (writes == null || writes.isEmpty()) {
            throw new IllegalArgumentException("writePages requires at least one page");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PageWrite w : writes) {
            requirePageScoped(w.identity());
            if (w.title() == null || w.title().isBlank()) {
                throw new IllegalArgumentException("page title must not be blank for " + w.identity().page().value());
            }
            if (w.body() == null) {
                throw new IllegalArgumentException("page body must not be null for " + w.identity().page().value());
            }
            String key = w.identity().workspace().value() + '' + w.identity().project().value()
                    + '' + w.identity().page().value();
            if (!seen.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate page path in one consolidation: " + w.identity().page().value());
            }
        }

        String commitMessage = writes.size() == 1
                ? "consolidate: " + writes.get(0).identity().page().value()
                : "consolidate: " + writes.size() + " pages";

        List<PageRecord> persisted = tx.execute(status -> {
            List<PageRecord> rows = new java.util.ArrayList<>(writes.size());
            List<com.agentmemory.wiki.MarkdownDocument> docs = new java.util.ArrayList<>(writes.size());
            // 1. Insert every page row first (no per-page callback): all in this one transaction.
            for (PageWrite w : writes) {
                String redacted = sanitizer.redactText(w.body());
                PageRecord row = pages.create(w.identity(), w.title(), redacted);
                rows.add(row);
                docs.add(com.agentmemory.wiki.WikiWriter.toDocument(row));
            }
            // 2. Write all files + ONE commit. A failure throws → the whole transaction (all rows) rolls
            //    back, and writeAllAndCommit removes any files it already wrote.
            try {
                wikiWriter.writeAllAndCommit(docs, commitMessage);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "consolidation wiki fan-out failed; rolling back " + writes.size() + " pages", e);
            }
            // 3. Audit each mutation in the same transaction.
            for (PageRecord row : rows) {
                writeAudit("page.consolidate", row.identity(), row.id().value(), actor,
                        "{\"existed\":" + (row.page().supersedes() != null) + ",\"pages\":" + writes.size() + "}");
            }
            return rows;
        });

        // 4. Maintain the wikilink graph for every page after commit (same seam as writePage), so
        //    forward links between the freshly fanned-out pages resolve (#27).
        for (PageRecord row : persisted) {
            links.syncPageLinks(row);
        }
        log.debug("memory_consolidate fan-out of {} pages by {}", persisted.size(), actor);
        return persisted;
    }

    /**
     * Delete the page at {@code identity} by exact path, idempotently, recording an audit entry. The
     * index rows are removed (cascading to links/embeddings), the wiki markdown file is removed and
     * the removal committed, and the mutation is audited. Deleting a missing path is a no-op success.
     *
     * @param identity page-scoped identity (path required); never null.
     * @param actor    who is performing the delete (e.g. {@link #ACTOR_MCP}).
     * @return whether a page actually existed and was removed ({@code false} = no-op success).
     */
    public boolean deletePage(Identity identity, String actor) {
        requirePageScoped(identity);
        String workspace = identity.workspace().value();
        String project = identity.project().value();
        String path = identity.page().value();

        return Boolean.TRUE.equals(tx.execute(status -> {
            // Remove every version row for this path (latest + history). The schema cascades to
            // links.from_page_id (CASCADE) and page_embeddings (CASCADE); backlinks via
            // links.to_page_id revert to deferred (SET NULL).
            int removed = jdbc.update(
                    "DELETE FROM pages WHERE workspace = ? AND project = ? AND path = ?",
                    workspace, project, path);
            boolean existed = removed > 0;

            // Remove the wiki file + commit the deletion inside the same transaction. A failure throws
            // and rolls the row deletes back (index and wiki stay consistent). No-op when absent.
            try {
                wikiWriter.deleteAndCommit(identity, "delete: " + path);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "wiki delete side effect failed; rolling back delete of " + path, e);
            }

            writeAudit("page.delete", identity, null, actor, "{\"existed\":" + existed + "}");
            log.debug("memory_delete_page {} by {} -> existed={}", path, actor, existed);
            return existed;
        }));
    }

    /**
     * Insert one {@code audit_log} row for a page mutation: the action, the 3-tuple identity (path
     * included — page mutations are page-scoped), the affected entity id when there is one, and the
     * actor + small structured detail. Runs in the caller's transaction.
     */
    private void writeAudit(String action, Identity identity, UUID entityId, String actor, String detailObj) {
        // detail is jsonb: merge the actor in with whatever small structured detail the caller passed.
        String detail = "{\"actor\":" + jsonString(actor) + ",\"detail\":" + detailObj + "}";
        jdbc.update(
                "INSERT INTO audit_log (id, workspace, project, path, action, entity_type, entity_id, detail) "
                        + "VALUES (?, ?, ?, ?, ?, 'page', ?, CAST(? AS jsonb))",
                Uuid7.randomUuid(),
                identity.workspace().value(), identity.project().value(), identity.page().value(),
                action, entityId, detail);
    }

    private static void requirePageScoped(Identity identity) {
        if (identity == null || !identity.isPageScoped()) {
            throw new IllegalArgumentException("identity must be page-scoped (path required)");
        }
    }

    /**
     * One page to create in a {@link #writePages multi-page fan-out}: its page-scoped identity, title
     * and (pre-redaction) markdown body.
     *
     * @param identity page-scoped identity (path required); never null.
     * @param title    the page title; never null/blank.
     * @param body     the markdown body (redacted by the service before persisting); never null.
     */
    public record PageWrite(Identity identity, String title, String body) {}

    /** Minimal JSON string encoder for the small audit {@code detail} tokens (or {@code null}). */
    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
