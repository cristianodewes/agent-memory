-- V2 — the top of the 3-tuple identity coordinate: `workspaces` and `projects`
-- (ARCHITECTURE §4.2; invariant #4 "typed (workspace, project, path) identity on every row").
--
-- Design goal #6 wants projects cheap to rename / move / purge. We therefore give each workspace
-- and project a stable surrogate `id` (UUIDv7, minted by the app — see core.Uuid7) and treat the
-- human `slug` as a mutable, UNIQUE natural key. Every downstream domain table denormalizes the
-- `(workspace, project, path)` *slugs* (mirroring core.Identity, which is slugs, not ids) AND keeps
-- a FK to these surrogate ids, so a rename is a single-row slug update with ON UPDATE CASCADE
-- propagating to children, and a purge is an ON DELETE CASCADE from the project row.
--
-- Slugs are the normalized form core.WorkspaceId / core.ProjectId already guarantee: trimmed,
-- ASCII-lower-cased, single segment (no '/', '\\', NUL). The CHECKs here are a defense-in-depth
-- backstop for direct SQL writes, not the primary validation (that lives in the typed value).

CREATE TABLE workspaces (
    id          uuid        PRIMARY KEY,
    slug        text        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT workspaces_slug_normalized
        CHECK (slug = lower(slug) AND slug <> '' AND slug !~ '[/\\\x00]')
);

COMMENT ON TABLE workspaces IS
    'Top coordinate of the 3-tuple identity. slug is the case-insensitive single-segment natural key (core.WorkspaceId); id is a stable UUIDv7 surrogate so renames do not cascade through slugs blindly.';

CREATE TABLE projects (
    id            uuid        PRIMARY KEY,
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON UPDATE CASCADE ON DELETE CASCADE,
    workspace     text        NOT NULL,
    slug          text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    -- A project slug is unique *within* a workspace (the same project name may exist under two
    -- workspaces), matching the (workspace, project) coordinate pair.
    CONSTRAINT projects_workspace_slug_unique UNIQUE (workspace, slug),
    CONSTRAINT projects_slug_normalized
        CHECK (slug = lower(slug) AND slug <> '' AND slug !~ '[/\\\x00]'),
    CONSTRAINT projects_workspace_normalized
        CHECK (workspace = lower(workspace) AND workspace <> '' AND workspace !~ '[/\\\x00]')
);

COMMENT ON TABLE projects IS
    'Second coordinate of the 3-tuple identity, scoped under a workspace. Denormalizes the workspace slug for the (workspace, project) pair and FK-references workspaces(id) for cascade rename/purge.';

CREATE INDEX projects_workspace_id_idx ON projects (workspace_id);
