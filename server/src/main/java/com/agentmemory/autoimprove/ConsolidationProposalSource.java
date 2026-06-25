package com.agentmemory.autoimprove;

import com.agentmemory.consolidate.ConsolidatedPages;
import com.agentmemory.consolidate.Consolidator;
import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link ProposalSource} (issue #30): the #19 {@link Consolidator} in <em>propose-only</em>
 * mode. For each freshly-finished session the scheduler reviews, it asks the consolidator to distil the
 * session's durable knowledge into pages ({@code concepts/}, {@code decisions/}, {@code gotchas/},
 * {@code procedures/}) <strong>without writing them</strong>, and maps each page onto a {@link ProposedWrite}
 * the approval gate stages in {@code pending_writes} (and the eval gate validates) before it ever touches
 * durable memory.
 *
 * <p>This is the human-in-the-loop counterpart of the automatic session-end consolidation
 * ({@code ConsolidationObservationListener}, which writes the fan-out directly): the same LLM distillation,
 * but every page is proposed for review/approval instead of committed. {@code multiPage} is {@code true} so a
 * session is proposed as its full fan-out — each page an independently approvable row.
 *
 * <p>Turning the #29 curator's <em>diagnostics</em> (cold pages, duplicate titles, dangling links) into
 * corrective <em>actions</em> (forget / merge / fix-link) is a distinct, richer action-shaped source — a
 * deliberate follow-up tracked separately (issue #101), not this v1 wiring.
 */
public class ConsolidationProposalSource implements ProposalSource {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationProposalSource.class);

    private final Consolidator consolidator;
    private final boolean multiPage;

    public ConsolidationProposalSource(Consolidator consolidator, boolean multiPage) {
        this.consolidator = consolidator;
        this.multiPage = multiPage;
    }

    @Override
    public List<ProposedWrite> proposalsFor(Scope scope, SessionId session) {
        List<ConsolidatedPages.Page> pages = consolidator.proposePages(scope, session, multiPage);
        if (pages.isEmpty()) {
            return List.of(); // no observations / nothing to distil — nothing to propose
        }
        List<ProposedWrite> proposals = new ArrayList<>(pages.size());
        for (ConsolidatedPages.Page p : pages) {
            proposals.add(new ProposedWrite(
                    p.path(), p.title(), p.body(), "page.edit",
                    "consolidated from session " + session.value()));
        }
        log.debug("auto-improve: consolidation proposed {} page(s) from session {}",
                proposals.size(), session.value());
        return proposals;
    }
}
