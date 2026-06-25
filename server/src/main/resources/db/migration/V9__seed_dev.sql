-- V9 — minimal dev/demo seed (issue #4: "a seed/dev migration is acceptable but must be
-- reversible/idempotent").
--
-- Seeds exactly one demo workspace + project so a fresh `docker compose up` has a navigable
-- coordinate to browse in /web before any real capture happens. Kept deliberately tiny and
-- IDEMPOTENT via fixed UUIDs + ON CONFLICT DO NOTHING, so re-running (or a manual replay) is a
-- no-op and it never clobbers a row a user may have edited. No pages/sessions are seeded, so the
-- demo coordinate is empty and harmless on a real install; it can be removed with a single
-- `DELETE FROM workspaces WHERE id = '00000000-0000-7000-8000-000000000001'` (projects cascade).

INSERT INTO workspaces (id, slug)
VALUES ('00000000-0000-7000-8000-000000000001', 'demo')
ON CONFLICT (id) DO NOTHING;

INSERT INTO projects (id, workspace_id, workspace, slug)
VALUES (
    '00000000-0000-7000-8000-000000000002',
    '00000000-0000-7000-8000-000000000001',
    'demo',
    'agent-memory'
)
ON CONFLICT (id) DO NOTHING;
