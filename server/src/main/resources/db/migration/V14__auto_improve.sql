-- V14 — auto-improve scheduler state (#30; ARCHITECTURE §2.2 self-improvement).
--
-- The proposal/approval store itself is the pre-existing `pending_writes` (V8, with its `eval_result`
-- slot for the #31 eval gate). This migration adds only what the out-of-band scheduler needs: a
-- per-project first-run watermark, per-session review claims, and a session link on proposals.

-- Per-project first-run cutoff: sessions finished at/before `established_at` are NOT auto-reviewed, so
-- enabling auto-improve on an existing project does not retro-review its whole history. One row per
-- project, written once on the first tick that sees the project.
CREATE TABLE auto_improve_watermark (
    workspace      text        NOT NULL,
    project        text        NOT NULL,
    established_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace, project)
);

COMMENT ON TABLE auto_improve_watermark IS
    'Per-project first-run cutoff (#30): finished sessions at/before established_at are not auto-reviewed.';

-- Per-session review claim: de-dupes concurrent/overlapping ticks (PRIMARY KEY on session_id) and caps
-- retries via `attempts` so a permanently-failing session does not retry forever.
CREATE TABLE auto_improve_session_review (
    session_id   uuid        PRIMARY KEY,
    workspace    text        NOT NULL,
    project      text        NOT NULL,
    status       text        NOT NULL DEFAULT 'claimed',   -- claimed -> done | failed
    attempts     int         NOT NULL DEFAULT 0,
    claimed_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    last_error   text,
    CONSTRAINT auto_improve_session_review_status_valid
        CHECK (status IN ('claimed', 'done', 'failed'))
);

COMMENT ON TABLE auto_improve_session_review IS
    'Per-session auto-improve review claim + outcome (#30): de-dupes ticks and caps retries via attempts.';

CREATE INDEX auto_improve_session_review_scope_idx
    ON auto_improve_session_review (workspace, project, status);

-- Tie a proposal back to the session that produced it — for targeted reruns (memory_auto_improve
-- --session-id), the report, and the audit trail. Nullable: pre-#30 proposals (none yet) have none.
ALTER TABLE pending_writes ADD COLUMN session_id uuid;
CREATE INDEX pending_writes_session_idx ON pending_writes (session_id);
