-- V15 — action-capable proposal kinds for the auto-improve loop (#101).
--
-- `pending_writes.kind` (V8) was free-form text with example values in a comment. The curator
-- corrective-action loop (#101) makes the kind load-bearing: the dispatching applier routes on it
-- (`page.edit` -> content upsert, `page.forget` -> forget sweep, `link.fix` -> wikilink prune). Promote
-- the vocabulary to a schema-level allowlist so an unknown/typo kind can never be persisted and silently
-- mis-dispatched, mirroring the `pending_writes_status_valid` CHECK already on the table.
--
-- Care with the CHECK: `page.edit` is the pre-existing content kind (#30/#100), so it MUST stay allowed
-- or the ALTER would reject the existing rows it produced. Deferred follow-up kinds (`page.merge` for
-- DUPLICATE_TITLE, `slot.refresh` for STALE_SLOT) are intentionally NOT listed — a kind is added here in
-- the same change that ships its source + applier (nothing dormant), kept in lockstep with `ProposalKinds`.

ALTER TABLE pending_writes
    ADD CONSTRAINT pending_writes_kind_valid
    CHECK (kind IN ('page.edit', 'page.forget', 'link.fix'));

COMMENT ON COLUMN pending_writes.kind IS
    'Proposal kind, dispatched by the applier (#30/#101): page.edit (content upsert), page.forget (forget sweep), link.fix (wikilink prune). Allowlisted by pending_writes_kind_valid.';
