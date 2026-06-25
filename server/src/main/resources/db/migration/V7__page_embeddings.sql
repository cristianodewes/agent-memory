-- V7 — `page_embeddings`: the pgvector semantic-recall arm (ARCHITECTURE §4.2, §3.3; DD-004).
--
-- EMBEDDING-DIM CONTRACT (single source of truth):
--   The pgvector column width is FIXED at DDL time and is 1024. This matches the default embedder
--   on the embeddings axis — Voyage `voyage-3` (llm.VoyageEmbedder.DEFAULT_DIMENSIONS = 1024, #6),
--   the dimension ARCHITECTURE §4.2 documents and Embedder#dimensions() returns. invariant #8
--   requires {provider, model, dim} denormalized next to every embedding, so those three columns
--   live here alongside the vector, and the CHECK pins `dim` to the column width.
--
--   To change the embedding model/width: (1) set the embeddings model + dimensions in config (#6),
--   then (2) add a NEW migration that recreates this column at the new width (vector columns cannot
--   be widened in place) and rebuilds the index. Migrations are append-only — never edit this file.

CREATE TABLE page_embeddings (
    id           uuid        PRIMARY KEY,
    page_id      uuid        NOT NULL REFERENCES pages (id) ON DELETE CASCADE,

    -- {provider, model, dim} denormalized next to the vector (invariant #8).
    provider     text        NOT NULL,        -- e.g. 'voyage' (llm provider key)
    model        text        NOT NULL,        -- e.g. 'voyage-3'
    dim          integer     NOT NULL,        -- must equal the vector column width below

    embedding    vector(1024) NOT NULL,       -- FIXED-width pgvector column (the dim contract)
    created_at   timestamptz NOT NULL DEFAULT now(),

    -- The denormalized dim must agree with the column width, or recall math is silently wrong.
    CONSTRAINT page_embeddings_dim_matches CHECK (dim = 1024),
    -- One embedding per (page version, provider, model): a page may carry vectors from more than
    -- one provider/model, but not duplicates of the same one.
    CONSTRAINT page_embeddings_unique_per_model UNIQUE (page_id, provider, model)
);

COMMENT ON TABLE page_embeddings IS
    'pgvector embeddings for semantic recall. embedding is vector(1024) — the dim contract (Voyage voyage-3, #6); {provider, model, dim} denormalized per invariant #8.';

-- HNSW index (recommended over ivfflat: better recall/latency, no training step). Cosine distance
-- (vector_cosine_ops) is the standard metric for normalized text embeddings; recall queries use the
-- matching `<=>` operator. m/ef_construction left at sensible pgvector defaults.
CREATE INDEX page_embeddings_hnsw
    ON page_embeddings USING hnsw (embedding vector_cosine_ops);

CREATE INDEX page_embeddings_page_idx ON page_embeddings (page_id);

-- Close the pages.embedding_id ref (declared NULLable in V3) now that the target table exists.
-- ON DELETE SET NULL: dropping an embedding row de-references the page without deleting it.
ALTER TABLE pages
    ADD CONSTRAINT pages_embedding_id_fk
    FOREIGN KEY (embedding_id) REFERENCES page_embeddings (id) ON DELETE SET NULL;
