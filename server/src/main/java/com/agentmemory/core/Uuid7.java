package com.agentmemory.core;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generator for <strong>UUID version 7</strong> identifiers (RFC 9562 §5.7): a 48-bit
 * Unix-millisecond timestamp followed by 74 random bits, with the version (7) and IETF
 * variant bits set. The leading timestamp makes v7 ids <em>k-sortable</em> — they sort by
 * creation time — which keeps the Postgres index for {@code sessions}/{@code observations}
 * append-friendly without a separate ordering column (ARCHITECTURE §4.2).
 *
 * <p>This lives in {@code core} on purpose: the domain owns its identity strategy and must not
 * pull a third-party id library or any IO to mint one (ARCHITECTURE §6 — "no IO"). The JDK
 * ships no v7 factory through {@link UUID} as of JDK 25, so we assemble the 128 bits by hand.
 *
 * <p>Layout (most-significant bit first):
 * <pre>
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                       unix_ts_ms (48 bits)                    |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |unix_ts_ms (cont.)| ver (4) |          rand_a (12 bits)        |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |var(2)|                    rand_b (62 bits)                    |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>The class is final and stateless; {@link #randomUuid()} is thread-safe ({@link SecureRandom}
 * is). Time monotonicity within the same millisecond is not enforced here — the 74 random bits
 * make intra-ms collisions astronomically unlikely, and the store assigns ids on the single
 * writer path where wall-clock regressions are not a concern for ordering correctness.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuid7() {
    }

    /**
     * Mints a fresh UUIDv7 using the current wall-clock time.
     *
     * @return a time-ordered {@link UUID} with version {@code 7} and the IETF variant.
     */
    public static UUID randomUuid() {
        return fromMillis(System.currentTimeMillis());
    }

    /**
     * Mints a UUIDv7 whose timestamp field is {@code unixMillis}. Exposed (package-private) so
     * tests can pin time and assert ordering/extraction deterministically.
     *
     * @param unixMillis Unix epoch milliseconds to embed in the 48 high bits (must be
     *                   non-negative and fit in 48 bits).
     * @return the assembled {@link UUID}.
     * @throws IllegalArgumentException if {@code unixMillis} does not fit in 48 unsigned bits.
     */
    static UUID fromMillis(long unixMillis) {
        if (unixMillis < 0 || unixMillis > 0xFFFF_FFFF_FFFFL) {
            throw new IllegalArgumentException("timestamp does not fit in 48 bits: " + unixMillis);
        }
        byte[] randomBytes = new byte[10];
        RANDOM.nextBytes(randomBytes);

        long msb = unixMillis << 16;                       // 48-bit ts into the top 48 bits
        msb |= (long) (randomBytes[0] & 0x0F) << 8;        // 4 bits of rand_a (low nibble of b0)
        msb |= randomBytes[1] & 0xFFL;                     // 8 more bits of rand_a
        msb &= ~(0xFL << 12);                              // clear the version nibble
        msb |= 0x7L << 12;                                 // set version = 7

        long lsb = 0;
        for (int i = 2; i < 10; i++) {                     // 8 bytes -> 64 bits of rand_b
            lsb = (lsb << 8) | (randomBytes[i] & 0xFFL);
        }
        lsb &= ~(0b11L << 62);                             // clear the two variant bits
        lsb |= 0b10L << 62;                                // set variant = IETF (10xx)

        return new UUID(msb, lsb);
    }

    /**
     * Extracts the embedded Unix-millisecond timestamp from a UUIDv7.
     *
     * @param uuid a UUID expected to be version 7.
     * @return the 48-bit timestamp as Unix epoch milliseconds.
     * @throws IllegalArgumentException if {@code uuid} is not version 7.
     */
    static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7: version=" + uuid.version());
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
