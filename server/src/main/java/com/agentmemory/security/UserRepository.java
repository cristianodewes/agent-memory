package com.agentmemory.security;

import com.agentmemory.core.UserId;
import com.agentmemory.core.Uuid7;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-writer SQL behind the per-user accounts (issue #39) over the {@code users} table. Stores only
 * the peppered token <em>hash</em> (never the token); the raw token is shown once at creation/rotation
 * and never persisted. Auth resolves a presented token by looking its hash up here.
 *
 * <p>Every mutating method is {@code @Transactional} (single writer, invariant #2). Username is the
 * unique natural key; {@link #insert} surfaces a duplicate as {@link DuplicateKeyException}.
 */
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Create a user with the given token hash. Mints a UUIDv7 id and starts the account {@code active}.
     *
     * @throws DuplicateKeyException if the username (or, improbably, the token hash) already exists.
     */
    @Transactional
    public void insert(UserId username, String tokenHash, Instant now) {
        jdbc.update(
                "INSERT INTO users (id, username, token_hash, status, created_at) "
                        + "VALUES (?, ?, ?, 'active', ?)",
                Uuid7.randomUuid(), username.value(), tokenHash, java.sql.Timestamp.from(now));
    }

    /** All users, newest first (for {@code user list}). */
    @Transactional(readOnly = true)
    public List<UserAccount> list() {
        return jdbc.query(
                "SELECT username, status, created_at, rotated_at, expires_at "
                        + "FROM users ORDER BY created_at DESC",
                MAPPER);
    }

    /**
     * Resolve a presented token hash to its <em>active, unexpired</em> user — the auth lookup. An
     * expired-status or past-{@code expires_at} account does not resolve, so a revoked token stops
     * working immediately.
     */
    @Transactional(readOnly = true)
    public Optional<UserId> resolveActive(String tokenHash, Instant now) {
        List<UserId> hits = jdbc.query(
                "SELECT username FROM users "
                        + "WHERE token_hash = ? AND status = 'active' "
                        + "  AND (expires_at IS NULL OR expires_at > ?)",
                (rs, n) -> UserId.of(rs.getString("username")),
                tokenHash, java.sql.Timestamp.from(now));
        return hits.stream().findFirst();
    }

    /** Flip a user's status ({@code active}↔{@code expired}); {@code true} if a row changed. */
    @Transactional
    public boolean setStatus(UserId username, String status) {
        return jdbc.update(
                "UPDATE users SET status = ? WHERE username = ?", status, username.value()) == 1;
    }

    /** Replace a user's token hash in place (rotate-token); {@code true} if the user exists. */
    @Transactional
    public boolean updateTokenHash(UserId username, String tokenHash, Instant now) {
        return jdbc.update(
                "UPDATE users SET token_hash = ?, rotated_at = ? WHERE username = ?",
                tokenHash, java.sql.Timestamp.from(now), username.value()) == 1;
    }

    /** @return whether a user with this username exists (any status). */
    @Transactional(readOnly = true)
    public boolean exists(UserId username) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE username = ?", Integer.class, username.value());
        return n != null && n > 0;
    }

    /** One {@code users} row as shown by {@code user list} (no secret material). */
    public record UserAccount(
            UserId username, String status, Instant createdAt, Instant rotatedAt, Instant expiresAt) {}

    private static final RowMapper<UserAccount> MAPPER = (rs, n) -> new UserAccount(
            UserId.of(rs.getString("username")),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            optInstant(rs.getTimestamp("rotated_at")),
            optInstant(rs.getTimestamp("expires_at")));

    private static Instant optInstant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
