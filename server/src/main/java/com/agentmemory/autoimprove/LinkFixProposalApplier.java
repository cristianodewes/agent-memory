package com.agentmemory.autoimprove;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.links.WikiLinkParser;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a {@link ProposalKinds#LINK_FIX} proposal (issue #101, curator {@code DANGLING_CROSS_PROJECT}
 * action): prune the dangling cross-project wikilink from the source page's body and persist the pruned
 * body as a new version through the normal write path. Because the {@code [[link]]} markup is removed
 * from the body — the source of truth (DD-002) — the link graph (#27) drops the broken edge when the
 * write re-syncs links, and a later reindex cannot resurrect it.
 *
 * <p>The page to fix is {@code write.path()}; the dangling target to prune travels in
 * {@code params[target]} as {@code workspace/project/path}. Idempotent: a missing source page or a body
 * with nothing left to prune is a no-op (no redundant version is written).
 */
public final class LinkFixProposalApplier implements ProposalApplier {

    private static final Logger log = LoggerFactory.getLogger(LinkFixProposalApplier.class);

    private final PageRepository pages;
    private final WikiLinkParser parser;
    private final MemoryWriteService writes;

    public LinkFixProposalApplier(PageRepository pages, WikiLinkParser parser, MemoryWriteService writes) {
        this.pages = pages;
        this.parser = parser;
        this.writes = writes;
    }

    @Override
    public void apply(Scope scope, ProposedWrite write) {
        Identity source = Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(write.path()));
        Optional<PageRecord> current = pages.readLatest(source);
        if (current.isEmpty()) {
            log.debug("link.fix {}: source page no longer exists — no-op", write.path());
            return;
        }
        String rawTarget = write.param(ProposalKinds.PARAM_TARGET);
        Identity target = parseTarget(rawTarget);
        if (target == null) {
            throw new IllegalArgumentException(
                    "link.fix proposal for " + write.path() + " has no valid '"
                            + ProposalKinds.PARAM_TARGET + "' param: " + rawTarget);
        }
        String body = current.get().page().body();
        String pruned = parser.stripLinksTo(source, body, target);
        if (pruned.equals(body)) {
            log.debug("link.fix {}: nothing to prune for target {} — no-op", write.path(), rawTarget);
            return;
        }
        writes.writePage(source, current.get().page().title(), pruned, "auto-improve");
        log.info("link.fix {}: pruned dangling link -> {}", write.path(), rawTarget);
    }

    /** Parse a {@code workspace/project/path} target id into a page-scoped identity; null if malformed. */
    private static Identity parseTarget(String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return null;
        }
        // path itself may contain '/', so only the first two segments are workspace/project.
        String[] seg = targetId.strip().split("/", 3);
        if (seg.length < 3) {
            return null;
        }
        try {
            return Identity.ofPage(WorkspaceId.of(seg[0]), ProjectId.of(seg[1]), PagePath.of(seg[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
