-- V11 — `pages.layer`: the retention layer that selects a page's decay regime (ARCHITECTURE §3.3;
-- ROADMAP #24).
--
-- #24 makes decay regime-aware: a volatile scratch note and a durable decision must not share a
-- half-life. Each page row carries the layer it was classified into (core.MemoryLayer) so the decay
-- math (com.agentmemory.store) and the forget sweep (#25) can apply the right regime per row without
-- re-deriving it. The four values mirror core.MemoryLayer.wire(); the CHECK keeps the column an
-- honest enum at the DB level.
--
-- Default 'episodic' is the safe general bucket: an existing row (or any insert that predates layer
-- classification, e.g. the #4 dev seed and the schema-test fixtures) gets a finite hot→cold regime
-- rather than either never decaying (semantic) or being treated as throwaway (working).

ALTER TABLE pages
    ADD COLUMN layer text NOT NULL DEFAULT 'episodic';

ALTER TABLE pages
    ADD CONSTRAINT pages_layer_valid
    CHECK (layer IN ('working', 'episodic', 'semantic', 'procedural'));

COMMENT ON COLUMN pages.layer IS
    'Retention layer (core.MemoryLayer): working|episodic|semantic|procedural. Selects the decay '
    'regime applied by the store decay math (#24) and the forget sweep (#25).';

-- Sweep (#25) and "cold pages" scans walk the latest pages of a layer ordered by recency of the
-- decay signals (last access, then age). A partial index on the latest rows keyed by
-- (workspace, project, layer) with the access/update recency makes that scan index-only-ish and
-- keeps superseded history out of the way.
CREATE INDEX pages_layer_decay_idx
    ON pages (workspace, project, layer, last_accessed_at NULLS FIRST, updated_at)
    WHERE is_latest;
