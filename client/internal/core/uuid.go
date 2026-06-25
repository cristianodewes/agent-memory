package core

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
)

// UUID is a 128-bit RFC 9562 identifier held big-endian, like java.util.UUID's two longs. The
// server mints all ids as version 7 (time-ordered); this type can hold and round-trip any UUID
// but NewUUIDv7 is the only constructor that generates one.
type UUID [16]byte

// NewUUIDv7 mints a fresh UUID version 7 (RFC 9562 §5.7): 48-bit Unix-millisecond timestamp in
// the high bits, then the version/variant bits, then random. Mirrors com.agentmemory.core.Uuid7.
func NewUUIDv7() UUID {
	return uuidV7FromMillis(nowUnixMilli())
}

// uuidV7FromMillis builds a v7 UUID with the given Unix-millisecond timestamp. Split out so tests
// can pin time and assert ordering/extraction deterministically.
func uuidV7FromMillis(unixMilli int64) UUID {
	var u UUID
	// 48-bit big-endian timestamp in bytes 0..5.
	u[0] = byte(unixMilli >> 40)
	u[1] = byte(unixMilli >> 32)
	u[2] = byte(unixMilli >> 24)
	u[3] = byte(unixMilli >> 16)
	u[4] = byte(unixMilli >> 8)
	u[5] = byte(unixMilli)

	// 74 random bits across bytes 6..15 (overwritten with version/variant below).
	if _, err := rand.Read(u[6:]); err != nil {
		// crypto/rand.Read never returns an error on supported platforms; treat as fatal.
		panic(fmt.Sprintf("core: crypto/rand failed: %v", err))
	}

	u[6] = (u[6] & 0x0F) | 0x70 // version 7 in the high nibble of byte 6
	u[8] = (u[8] & 0x3F) | 0x80 // IETF variant (10xx) in the high bits of byte 8
	return u
}

// Version returns the UUID version nibble (7 for ids minted by NewUUIDv7).
func (u UUID) Version() int {
	return int(u[6] >> 4)
}

// timestampMillis extracts the embedded 48-bit Unix-millisecond timestamp. Only meaningful for a
// v7 UUID; callers in tests guard on Version()==7.
func (u UUID) timestampMillis() int64 {
	return int64(u[0])<<40 |
		int64(u[1])<<32 |
		int64(u[2])<<24 |
		int64(u[3])<<16 |
		int64(u[4])<<8 |
		int64(u[5])
}

// String renders the canonical lowercase 8-4-4-4-12 form, identical to java.util.UUID.toString().
func (u UUID) String() string {
	var buf [36]byte
	hex.Encode(buf[0:8], u[0:4])
	buf[8] = '-'
	hex.Encode(buf[9:13], u[4:6])
	buf[13] = '-'
	hex.Encode(buf[14:18], u[6:8])
	buf[18] = '-'
	hex.Encode(buf[19:23], u[8:10])
	buf[23] = '-'
	hex.Encode(buf[24:36], u[10:16])
	return string(buf[:])
}

// IsZero reports whether the UUID is the all-zero value (an unset id).
func (u UUID) IsZero() bool {
	return u == UUID{}
}

// parseUUID parses the canonical 8-4-4-4-12 hyphenated form (case-insensitive). It is strict: any
// other length or layout is rejected, matching java.util.UUID.fromString's acceptance of the
// canonical shape used on the wire.
func parseUUID(s string) (UUID, error) {
	var u UUID
	if len(s) != 36 || s[8] != '-' || s[13] != '-' || s[18] != '-' || s[23] != '-' {
		return u, fmt.Errorf("core: invalid UUID %q", s)
	}
	groups := [][2]int{{0, 8}, {9, 13}, {14, 18}, {19, 23}, {24, 36}}
	dst := 0
	for _, g := range groups {
		n, err := hex.Decode(u[dst:], []byte(s[g[0]:g[1]]))
		if err != nil {
			return UUID{}, fmt.Errorf("core: invalid UUID %q: %w", s, err)
		}
		dst += n
	}
	return u, nil
}
