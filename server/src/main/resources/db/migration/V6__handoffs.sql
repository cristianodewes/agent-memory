-- V6 — `handoffs`: typed, LLM-written "where you left off" records (ARCHITECTURE §3.4, §4.2;
-- DD-005; core.Handoff). Project-scoped (no path). Single-use: written `open` at session end,
-- fetched-and-acked to `accepted` at the next session start, or `expired` if cancelled/superseded.
--
-- The two list fields are ALWAYS present ([] when empty, never null — serialization contract §1):
-- modeled as text[] NOT NULL DEFAULT '{}'. `status` is stored as text with a CHECK pinning the
-- strict core.HandoffStatus set (unlike observation kind, an unknown handoff status is a data error).

CREATE TABLE handoffs (
    id             uuid        PRIMARY KEY,
    workspace_id   uuid        NOT NULL REFERENCES workspaces (id) ON UPDATE CASCADE ON DELETE CASCADE,
    project_id     uuid        NOT NULL REFERENCES projects (id)   ON UPDATE CASCADE ON DELETE CASCADE,

    -- Project-scoped 3-tuple identity (no path).
    workspace      text        NOT NULL,
    project        text        NOT NULL,

    from_session   uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    status         text        NOT NULL,
    summary        text        NOT NULL,
    open_questions text[]      NOT NULL DEFAULT '{}',
    next_steps     text[]      NOT NULL DEFAULT '{}',
    created_at     timestamptz NOT NULL,
    accepted_at    timestamptz,                -- NULL while status = 'open'

    CONSTRAINT handoffs_status_valid CHECK (status IN ('open', 'accepted', 'expired')),
    -- accepted_at is set iff the handoff has been consumed.
    CONSTRAINT handoffs_accepted_at_consistency
        CHECK ((status = 'accepted') = (accepted_at IS NOT NULL))
);

COMMENT ON TABLE handoffs IS
    'Typed LLM-written handoffs (core.Handoff). Single-use; at most one open handoff per project (handoffs_one_open_per_project).';

-- Single-use discipline: at most one OPEN handoff per project at a time. A new handoff supersedes
-- the prior one (which must be expired/accepted first), so the next session-start fetch is
-- unambiguous.
CREATE UNIQUE INDEX handoffs_one_open_per_project
    ON handoffs (workspace, project)
    WHERE status = 'open';

CREATE INDEX handoffs_identity_idx ON handoffs (workspace, project, created_at DESC);
CREATE INDEX handoffs_from_session_idx ON handoffs (from_session);
