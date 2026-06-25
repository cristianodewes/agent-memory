-- V5 — `links`: directed wikilinks / cross-refs powering the graph-neighborhood recall arm
-- (ARCHITECTURE §4.2, §3.3; core.Link). Two shapes the contract calls out are modeled here:
--
--   * Forward / deferred links — `to_page_id` is NULLABLE: a page may link to a target that does
--     not exist yet. `target_resolved` is false until the page is created, at which point the store
--     fills `to_page_id`. The CHECK enforces core.Link's rule "targetResolved cannot be true
--     without a target".
--   * Cross-project scope — the target identity slugs may name a DIFFERENT (workspace, project)
--     than the source; nothing constrains them to match. The target may also be absent entirely
--     (a bare recorded anchor), so the target slug columns are nullable as a group.
--
-- `from_page_id` is the linking page *version* (core.Link.source is page-scoped, always present).

CREATE TABLE links (
    id                uuid     PRIMARY KEY,

    -- Source: always a page-scoped identity (core.Link#source). FK to the linking page version.
    from_page_id      uuid     NOT NULL REFERENCES pages (id) ON DELETE CASCADE,
    source_workspace  text     NOT NULL,
    source_project    text     NOT NULL,
    source_path       text     NOT NULL,

    -- Target: optional, page-scoped, possibly cross-project (core.Link#target). `to_page_id` is the
    -- resolved target page version, NULL for a deferred/forward link or a bare anchor.
    to_page_id        uuid     REFERENCES pages (id) ON DELETE SET NULL,
    target_workspace  text,
    target_project    text,
    target_path       text,

    anchor            text,                            -- wikilink text/token as written (nullable)
    target_resolved   boolean  NOT NULL DEFAULT false,

    created_at        timestamptz NOT NULL DEFAULT now(),

    -- core.Link invariant: cannot be resolved without a target identity.
    CONSTRAINT links_resolved_requires_target
        CHECK (NOT target_resolved OR target_path IS NOT NULL),
    -- A resolved link must actually point at a page version.
    CONSTRAINT links_resolved_requires_page
        CHECK (NOT target_resolved OR to_page_id IS NOT NULL),
    -- The three target identity slugs travel together (all set or all NULL).
    CONSTRAINT links_target_identity_complete CHECK (
        (target_workspace IS NULL AND target_project IS NULL AND target_path IS NULL)
        OR (target_workspace IS NOT NULL AND target_project IS NOT NULL AND target_path IS NOT NULL)
    )
);

COMMENT ON TABLE links IS
    'Directed wikilinks for graph-neighborhood recall (core.Link). to_page_id nullable for forward/deferred links; target identity may be cross-project.';

CREATE INDEX links_from_page_idx ON links (from_page_id);
CREATE INDEX links_to_page_idx ON links (to_page_id);
CREATE INDEX links_source_identity_idx ON links (source_workspace, source_project, source_path);
CREATE INDEX links_target_identity_idx ON links (target_workspace, target_project, target_path);
-- Deferred links awaiting resolution (the reindex/link-resolver scan, #14).
CREATE INDEX links_unresolved_idx ON links (target_workspace, target_project, target_path)
    WHERE NOT target_resolved AND target_path IS NOT NULL;
