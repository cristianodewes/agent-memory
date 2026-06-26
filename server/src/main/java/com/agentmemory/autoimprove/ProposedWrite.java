package com.agentmemory.autoimprove;

import java.util.Map;

/**
 * One proposed durable-knowledge change produced by reviewing a session or auditing a project (issues
 * #30 / #101) — the unit the approval gate records in {@code pending_writes} and (when approved) applies
 * through the {@link ProposalApplier} seam. Intentionally minimal and self-describing; each proposal
 * source maps its own output onto this.
 *
 * <p><strong>Content vs. action shape.</strong> The original (#30) proposal is content-shaped: a page
 * upsert carried by {@code path}/{@code title}/{@code body} ({@code kind = page.edit}). Issue #101 adds
 * corrective-<em>action</em> kinds ({@link ProposalKinds#PAGE_FORGET}, {@link ProposalKinds#LINK_FIX})
 * whose parameters do not fit {@code title}/{@code body}; those travel in {@link #params}. For an action
 * proposal {@code title}/{@code body} are a human-readable label/description for the audit + report, the
 * machine-readable arguments live in {@code params}, and {@code path} is the affected page. The
 * dispatching applier routes on {@code kind}.
 *
 * @param path      target page path (e.g. {@code concepts/recall.md}); required. For an action it is the
 *                  page the action operates on (the cold page to forget, the page whose link to prune).
 * @param title     proposed page title (content) or a short action label; required, non-blank.
 * @param body      proposed page body (content) or a human description of the action; required (may be empty).
 * @param kind      change kind recorded for the audit and used to dispatch the applier (e.g.
 *                  {@code page.edit}, {@code page.forget}, {@code link.fix}); required — see {@link ProposalKinds}.
 * @param rationale why the change was proposed (human-readable); may be null/blank.
 * @param params    action arguments keyed by name (e.g. {@code target} for {@code link.fix}); never null,
 *                  empty for a plain content upsert.
 */
public record ProposedWrite(
        String path, String title, String body, String kind, String rationale, Map<String, String> params) {

    public ProposedWrite {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("proposed write path must not be null or blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("proposed write title must not be null or blank");
        }
        path = path.strip();
        title = title.strip();
        body = body == null ? "" : body;
        kind = (kind == null || kind.isBlank()) ? ProposalKinds.PAGE_EDIT : kind.strip();
        rationale = (rationale == null || rationale.isBlank()) ? null : rationale.strip();
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /**
     * Content-shaped convenience constructor (no action params) — keeps every pre-#101 call site (the #30
     * content path, {@link CuratorProposalSource}, tests) source-compatible.
     */
    public ProposedWrite(String path, String title, String body, String kind, String rationale) {
        this(path, title, body, kind, rationale, Map.of());
    }

    /**
     * @param key the action parameter name.
     * @return the parameter value, or {@code null} when absent.
     */
    public String param(String key) {
        return params.get(key);
    }
}
