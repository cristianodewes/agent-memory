-- V10 — per-event idempotency key on `observations` (issue #8).
--
-- The client drains its local spool to POST /hook(/batch) at session boundaries (ARCHITECTURE
-- §2.1, §3.1). A drain can be retried after a partial failure, so the same spooled event may be
-- POSTed more than once. To make ingest idempotent (issue #8 acceptance: "replaying a batch creates
-- no duplicates"), the client stamps each spooled event with a stable id and the server dedupes on
-- it — rather than minting a fresh observation id per request, which would double-insert on replay.
--
-- `client_event_id` is the client's stable per-event id (hook.Payload#clientEventId). It is OPTIONAL:
-- a hand-built or legacy payload may omit it, in which case the event is always inserted (no dedupe).
-- Dedupe is scoped to the session — the unit the client drains — so the uniqueness is
-- (session_id, client_event_id) and a partial index excludes the NULLs (Postgres treats each NULL as
-- distinct anyway, but the partial predicate also keeps the index small and intent explicit).

ALTER TABLE observations
    ADD COLUMN client_event_id text;

COMMENT ON COLUMN observations.client_event_id IS
    'Client-supplied stable per-event id used to dedupe retried spool drains (issue #8). NULL ⇒ no idempotency key (always inserted).';

-- Enforces idempotency at the database, not in application code: a replayed event collides here and
-- the writer''s INSERT ... ON CONFLICT DO NOTHING makes the second attempt a no-op. Being a unique
-- index, it is also race-safe under concurrent posts of the same event (invariant #2 single-writer
-- serializes writes, but the constraint is the backstop).
CREATE UNIQUE INDEX observations_client_event_id_unique
    ON observations (session_id, client_event_id)
    WHERE client_event_id IS NOT NULL;
