-- V13 — `users` + actor attribution (issue #39; Survey §2.13).
--
-- A shared/team server needs per-user attribution: who produced each observation and each mutation.
-- This adds a server-global `users` table (single-tenant: one account namespace, no per-page RBAC)
-- and an `actor` column on the two primary-state tables that record activity — `observations` (raw
-- capture) and `audit_log` (every mutation). The actor is the user's slug (core.UserId), denormalized
-- like the workspace/project slugs so the audit trail is self-contained and survives a user rename.
--
-- A user authenticates with a per-user bearer token; only its HASH is stored (SHA-256 over
-- pepper||token, hex), never the token itself, so a database leak does not reveal credentials. The
-- pepper (agent-memory.auth.token-pepper) is server config: setting it turns on multi-user mode and
-- ties every stored hash to this server. Lifecycle is a status flip plus optional hard expiry:
--   active            -> usable
--   expired           -> revoked (expire); revive flips back to active
-- rotate-token replaces token_hash in place (same identity, new secret).

CREATE TABLE users (
    id          uuid        PRIMARY KEY,
    username    text        NOT NULL UNIQUE,     -- core.UserId slug; the audit_log.actor value
    token_hash  text        NOT NULL,            -- SHA-256(pepper || token) hex; never the raw token
    status      text        NOT NULL DEFAULT 'active',
    created_at  timestamptz NOT NULL DEFAULT now(),
    rotated_at  timestamptz,                     -- last rotate-token (NULL until first rotation)
    expires_at  timestamptz,                     -- optional hard expiry; NULL = no expiry

    CONSTRAINT users_username_normalized
        CHECK (username = lower(username) AND username <> '' AND username !~ '[/\\\x00]'),
    CONSTRAINT users_status_valid
        CHECK (status IN ('active', 'expired'))
);

COMMENT ON TABLE users IS
    'Per-user accounts on a shared server (issue #39, single-tenant). username is the core.UserId slug recorded as audit_log.actor; only the peppered token hash is stored, never the token.';

-- Auth resolves a presented token by its hash, so that lookup must be unique + indexed. A token hash
-- is globally unique (random 256-bit token); the unique constraint also stops two users sharing one.
CREATE UNIQUE INDEX users_token_hash_idx ON users (token_hash);

-- "List users" / housekeeping scans by recency.
CREATE INDEX users_created_idx ON users (created_at DESC);

-- Attribution column on the two activity tables. Nullable: loopback/no-auth and system-internal
-- writes have no authenticated actor, and all rows predating multi-user mode are NULL.
ALTER TABLE observations ADD COLUMN actor text;
ALTER TABLE audit_log    ADD COLUMN actor text;

COMMENT ON COLUMN observations.actor IS
    'The authenticated user (core.UserId slug) who produced this observation on a shared server (#39); NULL in single-user/loopback mode.';
COMMENT ON COLUMN audit_log.actor IS
    'The authenticated user (core.UserId slug) responsible for this mutation on a shared server (#39); NULL in single-user/loopback mode.';

-- Attribution queries ("what did this actor do") over the audit trail.
CREATE INDEX audit_log_actor_idx ON audit_log (actor, at DESC) WHERE actor IS NOT NULL;
