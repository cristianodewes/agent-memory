package com.agentmemory.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints the shared bearer token (issue #38). A high-entropy random string an operator sets as
 * {@code agent-memory.auth.token} (and uses as the {@code /web} Basic password) when exposing the
 * server. Exposed to the user via the {@code --generate-auth-token} startup flag (see
 * {@link com.agentmemory.AgentMemoryServerApplication}), which prints a fresh token and exits without
 * starting the server.
 */
public final class TokenGenerator {

    /** The CLI flag that triggers token generation instead of a normal boot. */
    public static final String FLAG = "--generate-auth-token";

    /** 32 random bytes = 256 bits of entropy — ample for a shared secret, URL/Basic safe once base64url'd. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenGenerator() {}

    /** @return a fresh URL-safe, unpadded base64 token (256 bits of entropy). */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** @return {@code true} if the program args request token generation (the {@link #FLAG}). */
    public static boolean isRequested(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (FLAG.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
