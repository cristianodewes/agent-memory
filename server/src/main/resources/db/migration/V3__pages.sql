-- V3 — `pages`: the versioned wiki-page index (ARCHITECTURE §4.2; DD-002).
--
-- DD-002: markdown in wiki/ is the source of truth; this table is a DERIVED index and is always
-- rebuildable from wiki/ via reindex (#14). Each row is ONE *version* of a page — its surrogate
-- key `id` is a core.PageId (UUIDv7). The page's human identity is the 3-tuple (workspace, project,
-- path); the version chain is modeled by `is_latest` + a `supersedes` pointer to the prior version,
-- mirroring core.Page exactly.
--
-- Access/decay columns (`access_count`, `last_accessed_at`) feed the decay-reinforcement bump on
-- recall (ARCHITECTURE §3.3). `embedding_id` is the optional ref into `page_embeddings` (V7);
-- it is a deferred FK because embeddings are a separate, default-on axis (DD-005) and may be absent
-- when recall degrades to FTS + graph.

CREATE TABLE pages (
    id              uuid        PRIMARY KEY,
    workspace_id    uuid        NOT NULL REFERENCES workspaces (id) ON UPDATE CASCADE ON DELETE CASCADE,
    project_id      uuid        NOT NULL REFERENCES projects (id)   ON UPDATE CASCADE ON DELETE CASCADE,

    -- 3-tuple identity, denormalized to slugs (mirrors core.Identity / invariant #4).
    workspace       text        NOT NULL,
    project         text        NOT NULL,
    path            text        NOT NULL,

    title           text        NOT NULL,
    body            text        NOT NULL,

    -- Version chain (core.Page#isLatest / #supersedes).
    is_latest       boolean     NOT NULL DEFAULT true,
    supersedes      uuid        REFERENCES pages (id) ON DELETE SET NULL,

    -- Decay-reinforcement access columns (ARCHITECTURE §3.3, §4.2).
    access_count    bigint      NOT NULL DEFAULT 0,
    last_accessed_at timestamptz,

    -- Optional ref into page_embeddings (V7). Set NULL when no embedding exists yet (deferred FK).
    embedding_id    uuid,

    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pages_access_count_nonneg CHECK (access_count >= 0)
);

COMMENT ON TABLE pages IS
    'Versioned wiki-page index (derived from wiki/, DD-002). One row per page version; (workspace, project, path) is the human identity, is_latest + supersedes the version chain.';

-- tsvector FTS over (title, body). A STORED generated column keeps the vector in lockstep with the
-- row (index commits with the data, invariant #3) with no trigger to drift. 'english' is the
-- default text-search config; title is weighted 'A', body 'B' for ts_rank ordering.
ALTER TABLE pages
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A')
        || setweight(to_tsvector('english', coalesce(body, '')), 'B')
    ) STORED;

COMMENT ON COLUMN pages.search_vector IS
    'Generated tsvector over (title^A, body^B) — the pages full-text arm (ARCHITECTURE §4.2 pages_fts).';

-- GIN index over the FTS column. Named pages_fts to match the §4.2 table-name vocabulary; the FTS
-- lives as a column + index on pages rather than a side table (a generated column cannot drift).
CREATE INDEX pages_fts ON pages USING gin (search_vector);

-- Trigram index on path for fuzzy path lookups (pg_trgm), complementing exact path access.
CREATE INDEX pages_path_trgm ON pages USING gin (path gin_trgm_ops);

-- Exactly one current version per (workspace, project, path). Enforces the is_latest invariant at
-- the DB level so a botched supersede can never leave two "latest" rows.
CREATE UNIQUE INDEX pages_latest_unique
    ON pages (workspace, project, path)
    WHERE is_latest;

-- Primary access pattern: "the latest page at (workspace, project, path)" (issue #4 acceptance).
CREATE INDEX pages_identity_latest_idx
    ON pages (workspace, project, path, is_latest);

-- Recently-updated latest pages (memory_recent, ARCHITECTURE §5.1) scoped per project.
CREATE INDEX pages_recent_idx
    ON pages (workspace, project, updated_at DESC)
    WHERE is_latest;

CREATE INDEX pages_supersedes_idx ON pages (supersedes);
