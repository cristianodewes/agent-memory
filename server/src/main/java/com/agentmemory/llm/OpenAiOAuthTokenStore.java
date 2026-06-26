package com.agentmemory.llm;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the OpenAI OAuth credential in the shared provider token file (issue #113),
 * matching the format the {@code agent-memory auth login openai-oauth} client writes and the
 * {@code ai-memory} reference uses.
 *
 * <p>The file is a single JSON object keyed by provider; the {@code "openai"} entry holds the
 * ChatGPT/Codex OAuth token:
 * <pre>{@code
 * {
 *   "openai": { "type": "oauth", "access": "...", "refresh": "...",
 *               "expires": 1730000000000, "accountId": "..." }
 * }
 * }</pre>
 * Other providers' entries (if any) are preserved on write. The long-lived refresh token lives here
 * because the server is the sole holder of state (DD-001) and the only component that refreshes the
 * short-lived access token — so when a refresh rotates the token, the server writes it back here.
 *
 * <p>Writes are atomic (temp file in the same directory, then rename) so a crash never leaves a
 * half-written credential, and the file is created {@code rw-------} where the platform supports
 * POSIX permissions (it holds a bearer credential).
 */
final class OpenAiOAuthTokenStore {

    /** Provider key under which the OpenAI entry lives in the shared token file. */
    private static final String ENTRY_KEY = "openai";

    /** Marker distinguishing an OAuth entry from, e.g., a static-key entry under the same provider. */
    private static final String ENTRY_TYPE = "oauth";

    private final Path file;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    OpenAiOAuthTokenStore(Path file) {
        this.file = file;
    }

    /** @return the token file path (for diagnostics / fail-fast messages). */
    Path path() {
        return file;
    }

    /**
     * Load the OpenAI OAuth token, or empty when the file is absent or carries no {@code oauth} entry
     * for {@code openai}.
     *
     * @throws LlmException if the file exists but cannot be read or parsed (a real misconfiguration).
     */
    Optional<Token> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(Files.readString(file));
        } catch (IOException | JacksonException e) {
            throw LlmException.permanent(
                    "Could not read openai-oauth token file '" + file + "': " + e.getMessage(), e);
        }
        JsonNode entry = root.get(ENTRY_KEY);
        if (entry == null || !entry.isObject() || !ENTRY_TYPE.equals(text(entry, "type"))) {
            return Optional.empty();
        }
        String access = text(entry, "access");
        String refresh = text(entry, "refresh");
        if (access == null || refresh == null) {
            return Optional.empty();
        }
        return Optional.of(new Token(access, refresh, longValue(entry, "expires"), text(entry, "accountId")));
    }

    /**
     * Persist {@code token} as the {@code openai} entry, preserving any other entries already in the
     * file. The write is atomic.
     *
     * @throws LlmException if the file cannot be written.
     */
    void save(Token token) {
        ObjectNode root;
        try {
            if (Files.exists(file)) {
                JsonNode existing = mapper.readTree(Files.readString(file));
                root = existing.isObject() ? (ObjectNode) existing : mapper.createObjectNode();
            } else {
                root = mapper.createObjectNode();
            }
        } catch (IOException | JacksonException e) {
            throw LlmException.permanent(
                    "Could not read openai-oauth token file '" + file + "' before update: " + e.getMessage(), e);
        }

        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", ENTRY_TYPE);
        entry.put("access", token.access());
        entry.put("refresh", token.refresh());
        entry.put("expires", token.expiresAtMs());
        if (token.accountId() != null && !token.accountId().isBlank()) {
            entry.put("accountId", token.accountId());
        }
        root.set(ENTRY_KEY, entry);

        writeAtomically(mapper.writeValueAsString(root));
    }

    private void writeAtomically(String json) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(parent, ".auth", ".tmp");
            restrictPermissions(tmp);
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw LlmException.permanent(
                    "Could not write openai-oauth token file '" + file + "': " + e.getMessage(), e);
        }
    }

    /** Best-effort {@code rw-------} on platforms with POSIX permissions; a no-op elsewhere (Windows). */
    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // POSIX permissions unavailable (e.g. Windows) — the parent dir's ACLs apply.
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.stringValue() : null;
    }

    private static long longValue(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isIntegralNumber() ? v.longValue() : 0L;
    }

    /**
     * The stored OpenAI OAuth token. {@code expiresAtMs} is the absolute access-token expiry in epoch
     * milliseconds; {@code accountId} is the optional ChatGPT account/workspace id sent on the
     * {@code chatgpt-account-id} header.
     */
    record Token(String access, String refresh, long expiresAtMs, String accountId) {
    }
}
