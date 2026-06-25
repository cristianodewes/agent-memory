package com.agentmemory.security;

import com.agentmemory.core.UserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * Per-user account lifecycle + token resolution on a shared server (issue #39). Each user gets a
 * high-entropy bearer token ({@link TokenGenerator}, 256-bit CSPRNG) shown <em>once</em> at creation
 * or rotation; only its hash is stored. Auth later resolves a presented token by hashing it the same
 * way and looking it up ({@link #resolveToken}).
 *
 * <h2>Why a plain hash + pepper (not bcrypt/argon2)</h2>
 * These tokens are machine-generated with 256 bits of entropy, so an attacker cannot guess or
 * dictionary them — a slow password-KDF buys nothing here, and auth runs on every request. A single
 * SHA-256 over {@code pepper || token} is the right primitive: it makes a leaked {@code users} table
 * non-reversible, and the server-side {@link #pepper} (never in the DB) defeats precomputation and ties
 * every hash to this deployment. The pepper is also the multi-user switch — it is only set in
 * multi-user mode (its presence is what activates per-user auth).
 */
public class UserService {

    private final UserRepository repo;
    private final byte[] pepper;
    private final Clock clock;

    public UserService(UserRepository repo, String pepper, Clock clock) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalArgumentException("token pepper must be set for multi-user mode");
        }
        this.repo = repo;
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /**
     * Create a user and issue its first token.
     *
     * @return the username + the raw token (returned only here — it is never stored or shown again).
     * @throws UserExistsException if the username is already taken.
     */
    public IssuedToken add(UserId username) {
        String token = TokenGenerator.generate();
        try {
            repo.insert(username, hash(token), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new UserExistsException(username);
        }
        return new IssuedToken(username, token);
    }

    /** @return all users, newest first (no secret material). */
    public List<UserRepository.UserAccount> list() {
        return repo.list();
    }

    /** Revoke a user (status → expired); its token stops resolving immediately. */
    public boolean expire(UserId username) {
        return repo.setStatus(username, "expired");
    }

    /** Re-activate a previously expired user (status → active). */
    public boolean revive(UserId username) {
        return repo.setStatus(username, "active");
    }

    /**
     * Issue a fresh token for an existing user, invalidating the old one.
     *
     * @return the username + new raw token, or empty if no such user.
     */
    public Optional<IssuedToken> rotateToken(UserId username) {
        String token = TokenGenerator.generate();
        if (!repo.updateTokenHash(username, hash(token), clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new IssuedToken(username, token));
    }

    /**
     * Resolve a presented raw token to its active, unexpired user — the auth lookup. Empty when the
     * token matches no active user (wrong, revoked, or expired).
     */
    public Optional<UserId> resolveToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return repo.resolveActive(hash(rawToken), clock.instant());
    }

    /** SHA-256 over {@code pepper || token}, hex-encoded — the stored/looked-up form. */
    private String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(pepper);
            md.update((byte) ':');
            md.update(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }

    /** A newly issued credential: the raw {@code token} is the only time it is ever revealed. */
    public record IssuedToken(UserId username, String token) {}

    /** Thrown when creating a user whose username already exists (maps to {@code 409}). */
    public static final class UserExistsException extends RuntimeException {
        public UserExistsException(UserId username) {
            super("user already exists: " + username.value());
        }
    }
}
