package com.agentmemory.autoimprove;

import com.agentmemory.core.SessionId;
import com.agentmemory.eval.EvalGate;
import com.agentmemory.eval.EvalProposal;
import com.agentmemory.eval.EvalVerdict;
import com.agentmemory.recall.Scope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The approval gate (issue #30): records every proposed durable-knowledge edit in {@code pending_writes}
 * and decides its fate by {@link AutoImproveProperties#requireApproval()} — hold it as {@code proposed}
 * for human review, or apply it immediately through the normal write path (the default). The audit trail
 * is the {@code pending_writes} row itself.
 *
 * <p>Every apply is gated by the executable {@link EvalGate} (#31): immediately before a write, the
 * proposal is evaluated and a {@link EvalVerdict.Decision#BLOCKED} verdict stops it (the row is marked
 * {@code rejected}, never applied); {@code PASSED}/{@code SKIPPED} proceed. The verdict — when the gate
 * actually ran — is recorded in the row's {@code eval_result} slot (the audit trail). The eval gate is
 * off by default (config), so by default every proposal is {@code SKIPPED} and applies as before.
 *
 * <p>Applying goes through the injected {@link ProposalApplier} seam (production: the #19/#20 write
 * service), so the gate's decision logic is independent of the write stack.
 */
public class AutoImproveGate {

    private static final Logger log = LoggerFactory.getLogger(AutoImproveGate.class);

    private final JdbcPendingWriteRepository pending;
    private final ProposalApplier applier;
    private final AutoImproveProperties props;
    private final EvalGate evalGate;
    private final JsonMapper json = JsonMapper.builder().build();

    public AutoImproveGate(
            JdbcPendingWriteRepository pending, ProposalApplier applier, AutoImproveProperties props,
            EvalGate evalGate) {
        this.pending = pending;
        this.applier = applier;
        this.props = props;
        this.evalGate = evalGate;
    }

    /**
     * Put one proposed edit through the gate.
     *
     * @param scope   the project; never null.
     * @param session the session it came from, or null.
     * @param write   the proposed edit; never null.
     * @return the recorded proposal id and its resulting status ({@code PROPOSED} when held,
     *     {@code APPLIED} when auto-applied, {@code REJECTED} when the eval gate blocks it).
     * @throws RuntimeException if the auto-apply write fails — the proposal row is left {@code proposed}
     *     (not lost), and the caller (scheduler) records the session as failed for a bounded retry.
     */
    public Decision submit(Scope scope, SessionId session, ProposedWrite write) {
        if (scope == null || write == null) {
            throw new IllegalArgumentException("scope and write must not be null");
        }
        UUID id = pending.propose(scope, session == null ? null : session.value(), write);
        if (props.requireApproval()) {
            log.debug("auto-improve: holding proposal {} for {} (require_approval=true)", id, write.path());
            return new Decision(id, PendingWriteStatus.PROPOSED);
        }
        // Default: validate through the eval gate, then apply through the normal write path.
        return applyGated(id, scope, write);
    }

    /** Recent proposals in a project (newest first) — the {@code auto-improve-report} surface. */
    public List<PendingWriteRecord> report(Scope scope, int limit) {
        return pending.recent(scope, limit);
    }

    /**
     * Approve a held proposal: validate it through the eval gate and, unless blocked, apply it through
     * the normal write path. The human-review counterpart of an auto-applied submit — same eval-gated
     * write path, just gated on an explicit decision. Only a {@code proposed} row can be approved; a
     * blocked proposal becomes {@code rejected} instead of applied.
     *
     * @param id the proposal id.
     * @return the updated record (applied, or rejected if the eval gate blocked it).
     * @throws IllegalArgumentException if no proposal has that id.
     * @throws IllegalStateException    if the proposal is not in the {@code proposed} state.
     * @throws RuntimeException         if the apply fails — the row is left {@code proposed} (not lost).
     */
    public PendingWriteRecord approve(UUID id) {
        PendingWriteRecord rec = require(id, PendingWriteStatus.PROPOSED, "approved");
        applyGated(id, Scope.of(rec.workspace(), rec.project()), toProposedWrite(rec));
        return pending.findById(id).orElseThrow();
    }

    /**
     * Reject a held proposal: mark it {@code rejected}, leaving memory untouched. Only a {@code proposed}
     * row can be rejected.
     *
     * @param id the proposal id.
     * @return the updated record.
     * @throws IllegalArgumentException if no proposal has that id.
     * @throws IllegalStateException    if the proposal is not in the {@code proposed} state.
     */
    public PendingWriteRecord reject(UUID id) {
        PendingWriteRecord rec = require(id, PendingWriteStatus.PROPOSED, "rejected");
        pending.markRejected(id, null);
        log.info("auto-improve: rejected proposal {} ({})", id, rec.path());
        return pending.findById(id).orElseThrow();
    }

    private PendingWriteRecord require(UUID id, PendingWriteStatus expected, String verb) {
        PendingWriteRecord rec = pending.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no such proposal: " + id));
        if (rec.status() != expected) {
            throw new IllegalStateException(
                    "proposal " + id + " is '" + rec.status().db() + "', only a '" + expected.db()
                            + "' proposal can be " + verb);
        }
        return rec;
    }

    /** Rebuild the {@link ProposedWrite} from a stored row: {@code path}/{@code kind}/{@code rationale} are columns; {@code title}/{@code body}/{@code params} live in the {@code proposal} JSON. */
    private ProposedWrite toProposedWrite(PendingWriteRecord rec) {
        JsonNode p = json.readTree(rec.proposal());
        String title = str(p == null ? null : p.get("title"), rec.path());
        String body = str(p == null ? null : p.get("body"), "");
        Map<String, String> params = readParams(p == null ? null : p.get("params"));
        return new ProposedWrite(rec.path(), title, body, rec.kind(), rec.rationale(), params);
    }

    private static String str(JsonNode node, String fallback) {
        return node != null && node.isString() ? node.stringValue() : fallback;
    }

    /** Read the action {@code params} object back into a string map (empty for a content proposal). */
    private Map<String, String> readParams(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = json.convertValue(node, Map.class);
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    /**
     * Map a proposal to its gate-facing {@link EvalProposal}, kind-aware so the gate sees the real action
     * (#101): a content {@code page.edit} is an {@code upsert} (unchanged from #30); {@code page.forget}
     * and {@code link.fix} carry their own action verb. The {@code path} is always the affected page, so
     * the gate's prefix selection ({@link EvalGate}) governs every kind uniformly.
     */
    private static EvalProposal toEvalProposal(ProposedWrite write) {
        String action;
        if (ProposalKinds.PAGE_FORGET.equals(write.kind())) {
            action = ProposalKinds.PAGE_FORGET;
        } else if (ProposalKinds.LINK_FIX.equals(write.kind())) {
            action = ProposalKinds.LINK_FIX;
        } else {
            action = "upsert";
        }
        return new EvalProposal(write.path(), write.title(), write.body(), action);
    }

    /**
     * Validate {@code write} through the eval gate, then either apply it (PASSED/SKIPPED) or reject it
     * (BLOCKED — fail-closed). The verdict is recorded in {@code eval_result} whenever the gate actually
     * ran ({@code null} for SKIPPED, i.e. the gate is off or the path is out of scope — the default).
     */
    private Decision applyGated(UUID id, Scope scope, ProposedWrite write) {
        EvalVerdict verdict = evalGate.evaluate(toEvalProposal(write));
        String verdictJson =
                verdict.decision() == EvalVerdict.Decision.SKIPPED ? null : verdictJson(verdict);
        if (verdict.blocked()) {
            pending.markRejected(id, verdictJson);
            log.info("auto-improve: eval gate BLOCKED proposal {} ({}): {}",
                    id, write.path(), verdict.reasons());
            return new Decision(id, PendingWriteStatus.REJECTED);
        }
        applier.apply(scope, write);
        pending.markApplied(id, verdictJson);
        log.info("auto-improve: applied proposal {} ({}){}", id, write.path(),
                verdict.decision() == EvalVerdict.Decision.PASSED ? " [eval passed]" : "");
        return new Decision(id, PendingWriteStatus.APPLIED);
    }

    /** Serialize a verdict for the {@code eval_result} audit slot. */
    private String verdictJson(EvalVerdict verdict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decision", verdict.decision().name());
        m.put("reasons", verdict.reasons());
        return json.writeValueAsString(m);
    }

    /** The gate's decision for one proposal. */
    public record Decision(UUID id, PendingWriteStatus status) {
        public boolean applied() {
            return status == PendingWriteStatus.APPLIED;
        }

        public boolean held() {
            return status == PendingWriteStatus.PROPOSED;
        }

        public boolean rejected() {
            return status == PendingWriteStatus.REJECTED;
        }
    }
}
