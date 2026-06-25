-- V8 — `audit_log` and `pending_writes` (ARCHITECTURE §4.2).
--
-- `audit_log`: every mutation, addressable by `at DESC` — primary state (covered by backup/restore,
-- not reindex). `pending_writes`: self-improvement proposals moving through the approval gate
-- (ARCHITECTURE §2.2 self-improvement; §5.1 memory_auto_improve), with an optional executable
-- eval-gate result captured before apply.

CREATE TABLE audit_log (
    id           uuid        PRIMARY KEY,

    -- 3-tuple identity; path is nullable because some mutations are project-scoped, not page-scoped.
    workspace    text        NOT NULL,
    project      text        NOT NULL,
    path         text,

    action       text        NOT NULL,        -- e.g. 'page.write', 'handoff.accept', 'forget.sweep'
    entity_type  text,                        -- e.g. 'page', 'observation', 'handoff'
    entity_id    uuid,                        -- the affected row, when it has a surrogate id
    detail       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    at           timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_log IS
    'Every mutation, addressable by at DESC (ARCHITECTURE §4.2). Primary state; detail is structured jsonb.';

CREATE INDEX audit_log_at_idx ON audit_log (at DESC);
CREATE INDEX audit_log_identity_idx ON audit_log (workspace, project, at DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id);

CREATE TABLE pending_writes (
    id           uuid        PRIMARY KEY,

    -- 3-tuple identity; page-scoped proposals carry a path, broader ones leave it NULL.
    workspace    text        NOT NULL,
    project      text        NOT NULL,
    path         text,

    -- Approval-gate lifecycle: proposed -> approved -> applied, or rejected.
    status       text        NOT NULL DEFAULT 'proposed',
    kind         text        NOT NULL,        -- e.g. 'page.edit', 'link.add', 'lint.fix'
    proposal     jsonb       NOT NULL,        -- the structured proposed change (LLM JSON, inv. #7)
    rationale    text,                        -- why the change was proposed
    eval_result  jsonb,                       -- optional executable eval-gate outcome before apply

    created_at   timestamptz NOT NULL DEFAULT now(),
    decided_at   timestamptz,                 -- when approved/rejected
    applied_at   timestamptz,                 -- when the approved change was written

    CONSTRAINT pending_writes_status_valid
        CHECK (status IN ('proposed', 'approved', 'rejected', 'applied'))
);

COMMENT ON TABLE pending_writes IS
    'Self-improvement proposals awaiting/approved through the gate (ARCHITECTURE §4.2). proposal is structured LLM JSON; eval_result holds an optional executable gate outcome.';

CREATE INDEX pending_writes_status_idx ON pending_writes (status, created_at DESC);
CREATE INDEX pending_writes_identity_idx ON pending_writes (workspace, project, created_at DESC);
