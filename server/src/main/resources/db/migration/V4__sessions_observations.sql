-- V4 — `sessions` and `observations`: the hook capture log (ARCHITECTURE §4.2, §3.1).
--
-- Unlike `pages`, these are PRIMARY (not derived) state — they are the raw audit the LLM later
-- compiles into pages, and are covered by backup/restore rather than reindex (DD-002 note). Both
-- are project-scoped (core.Session / core.Observation carry a project-scoped identity: no path).

CREATE TABLE sessions (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON UPDATE CASCADE ON DELETE CASCADE,
    project_id    uuid        NOT NULL REFERENCES projects (id)   ON UPDATE CASCADE ON DELETE CASCADE,

    -- Project-scoped 3-tuple identity (no path).
    workspace     text        NOT NULL,
    project       text        NOT NULL,

    agent         text,                       -- e.g. 'claude-code'; nullable (core.Session#agent)
    started_at    timestamptz NOT NULL,
    ended_at      timestamptz,                -- NULL while the session is open (core.Session#isOpen)

    CONSTRAINT sessions_time_order CHECK (ended_at IS NULL OR ended_at >= started_at)
);

COMMENT ON TABLE sessions IS
    'Agent runs grouping observations between session-start and session-end (primary capture state). Project-scoped; ended_at NULL while open.';

CREATE INDEX sessions_identity_idx ON sessions (workspace, project, started_at DESC);
-- Partial index for the "currently-open session for this project" lookup the MCP scope resolver uses.
CREATE INDEX sessions_open_idx ON sessions (workspace, project) WHERE ended_at IS NULL;

CREATE TABLE observations (
    id            uuid        PRIMARY KEY,
    session_id    uuid        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON UPDATE CASCADE ON DELETE CASCADE,
    project_id    uuid        NOT NULL REFERENCES projects (id)   ON UPDATE CASCADE ON DELETE CASCADE,

    -- Project-scoped 3-tuple identity (no path).
    workspace     text        NOT NULL,
    project       text        NOT NULL,

    -- core.ObservationKind canonical wire token (kebab-case). Stored as text, not a PG enum, so a
    -- newer client emitting an unknown kind never blocks ingest (the lenient parse maps it to
    -- 'other' before it ever reaches here); the CHECK pins the canonical set as a backstop.
    kind          text        NOT NULL,
    source_event  text,                       -- raw agent-native event name pre-canonicalization
    extension     text,                       -- third-party namespace (ARCHITECTURE §5.4)
    payload       text        NOT NULL,       -- captured text (sanitized upstream, DD-010)
    created_at    timestamptz NOT NULL,

    CONSTRAINT observations_kind_canonical CHECK (kind IN (
        'session-start', 'user-prompt', 'pre-tool-use', 'post-tool-use',
        'pre-compact', 'notification', 'stop', 'session-end', 'other'
    ))
);

COMMENT ON TABLE observations IS
    'One captured agent lifecycle event under a session (primary capture state). kind is the canonical core.ObservationKind wire token; payload is sanitized upstream (DD-010).';

-- tsvector over the raw payload — the bounded raw-observation fallback arm of recall (§4.2).
ALTER TABLE observations
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(payload, ''))) STORED;

COMMENT ON COLUMN observations.search_vector IS
    'Generated tsvector over payload — the raw-observation full-text fallback (ARCHITECTURE §4.2 observations_fts).';

-- GIN over the FTS column. Named observations_fts to match the §4.2 vocabulary.
CREATE INDEX observations_fts ON observations USING gin (search_vector);

CREATE INDEX observations_session_idx ON observations (session_id, created_at);
CREATE INDEX observations_identity_idx ON observations (workspace, project, created_at DESC);
