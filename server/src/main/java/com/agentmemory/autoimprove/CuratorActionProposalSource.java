package com.agentmemory.autoimprove;

import com.agentmemory.curate.CuratorFinding;
import com.agentmemory.curate.CuratorReport;
import com.agentmemory.curate.CuratorService;
import com.agentmemory.recall.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the #29 rule-based curator's findings into corrective-<em>action</em> proposals, one per
 * actionable finding (issue #101) — the action-shaped progression of #100's report-shaped
 * {@link CuratorProposalSource}. Where #100 renders every finding into a single {@code _lint/report.md}
 * content page (documenting the issues), this maps each finding the loop can fix into its own
 * {@link ProposedWrite} action the {@link DispatchingProposalApplier} can execute:
 *
 * <ul>
 *   <li>{@code COLD_EPISODIC} → {@link ProposalKinds#PAGE_FORGET} (soft-delete the cold page).</li>
 *   <li>{@code DANGLING_CROSS_PROJECT} → {@link ProposalKinds#LINK_FIX} (prune the broken link; the
 *       dangling target travels in {@code params[target]}).</li>
 * </ul>
 *
 * <p><strong>Deferred (declared follow-up, nothing dormant).</strong> {@code DUPLICATE_TITLE} →
 * {@code page.merge} and {@code STALE_SLOT} → {@code slot.refresh} are larger appliers (merge must
 * combine bodies + redirect backlinks + forget the loser; slot-refresh needs the slot's regeneration
 * source) and are intentionally not emitted here yet — those findings simply produce no action until the
 * follow-up lands. The LLM contradiction pass stays out of this loop (zero-cost, on-demand via
 * {@code memory_lint}), exactly like #100.
 *
 * <p>This is <strong>scope-level</strong> (it audits a whole project), so it exposes {@link
 * #actionsFor(Scope)} rather than the per-session {@link ProposalSource#proposalsFor}: the curator-action
 * cadence is driven per-scope by the {@link CuratorActionScheduler}, distinct from the per-finished-session
 * {@link AutoImproveScheduler}.
 */
public class CuratorActionProposalSource {

    private static final Logger log = LoggerFactory.getLogger(CuratorActionProposalSource.class);

    private final CuratorService curator;

    public CuratorActionProposalSource(CuratorService curator) {
        this.curator = curator;
    }

    /**
     * Audit one project and return one corrective action per actionable finding (possibly empty).
     *
     * @param scope the project to audit; never null.
     * @return the corrective-action proposals; never null.
     */
    public List<ProposedWrite> actionsFor(Scope scope) {
        CuratorReport report = curator.curate(scope);
        if (report.isClean()) {
            return List.of();
        }
        List<ProposedWrite> actions = new ArrayList<>();
        for (CuratorFinding f : report.findings()) {
            ProposedWrite action = toAction(f);
            if (action != null) {
                actions.add(action);
            }
        }
        if (!actions.isEmpty()) {
            log.debug("curator-actions: {} actionable finding(s) in {}/{}",
                    actions.size(), scope.workspaceSlug(), scope.projectSlug());
        }
        return actions;
    }

    /** Map one finding to its corrective action, or null when the rule has no (implemented) action. */
    private ProposedWrite toAction(CuratorFinding f) {
        return switch (f.rule()) {
            case COLD_EPISODIC -> new ProposedWrite(
                    f.path(), "Forget cold page: " + f.path(), f.detail(),
                    ProposalKinds.PAGE_FORGET, "curator " + f.rule() + ": " + f.detail());
            case DANGLING_CROSS_PROJECT -> linkFix(f);
            // Deferred to a #101 follow-up (declared, not dormant): no action emitted yet.
            case DUPLICATE_TITLE, STALE_SLOT -> null;
        };
    }

    private ProposedWrite linkFix(CuratorFinding f) {
        String target = parseTarget(f.detail());
        if (target == null) {
            log.debug("curator-actions: could not extract a dangling target from '{}' — skipping link.fix",
                    f.detail());
            return null;
        }
        return new ProposedWrite(
                f.path(), "Prune dangling link in: " + f.path(), f.detail(),
                ProposalKinds.LINK_FIX, "curator " + f.rule() + ": " + f.detail(),
                Map.of(ProposalKinds.PARAM_TARGET, target));
    }

    /**
     * Extract the dangling target id ({@code workspace/project/path}) from a {@code DANGLING_CROSS_PROJECT}
     * finding detail, which {@link CuratorService} formats as
     * {@code "dangling cross-project link -> <target> (unresolved Nd)"}.
     *
     * @param detail the finding detail.
     * @return the target id, or null when it cannot be located.
     */
    static String parseTarget(String detail) {
        if (detail == null) {
            return null;
        }
        int arrow = detail.indexOf("-> ");
        if (arrow < 0) {
            return null;
        }
        String rest = detail.substring(arrow + 3).trim();
        int paren = rest.indexOf(" (");
        String target = (paren < 0 ? rest : rest.substring(0, paren)).trim();
        return target.isEmpty() ? null : target;
    }
}
