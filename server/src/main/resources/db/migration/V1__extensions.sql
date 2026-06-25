-- V1 — Postgres extensions the derived index depends on (ARCHITECTURE §4.2, DD-004).
--
-- `vector` (pgvector) backs semantic recall on `page_embeddings`; `pg_trgm` backs trigram
-- similarity / fuzzy lookups that complement the `tsvector` full-text arm. Both ship in the
-- `pgvector/pgvector:pg16` image used by compose and the Testcontainers migration test, so
-- `CREATE EXTENSION` succeeds without superuser gymnastics in either environment.
--
-- Migrations are append-only (DD-002 / issue #4 implementation note): never edit a released
-- migration; add a new V-numbered file instead.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
