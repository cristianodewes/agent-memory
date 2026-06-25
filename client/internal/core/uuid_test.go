package core

import (
	"encoding/json"
	"testing"
)

// UUIDv7 properties mirror com.agentmemory.core ValueTypesTest: version 7, IETF variant, embedded
// timestamp, k-sortability, and canonical string round-trip.

func TestUUIDv7VersionAndVariant(t *testing.T) {
	u := NewUUIDv7()
	if u.Version() != 7 {
		t.Errorf("version = %d, want 7", u.Version())
	}
	// IETF variant: top two bits of byte 8 are 10.
	if u[8]&0xC0 != 0x80 {
		t.Errorf("variant bits = %#x, want 0x80", u[8]&0xC0)
	}
}

func TestUUIDv7EmbedsTimestampAndIsTimeOrdered(t *testing.T) {
	earlier := int64(1_700_000_000_000)
	later := earlier + 5_000
	a := uuidV7FromMillis(earlier)
	b := uuidV7FromMillis(later)

	if got := a.timestampMillis(); got != earlier {
		t.Errorf("timestampMillis(a) = %d, want %d", got, earlier)
	}
	if got := b.timestampMillis(); got != later {
		t.Errorf("timestampMillis(b) = %d, want %d", got, later)
	}
	// k-sortable: the earlier id sorts before the later one lexicographically (timestamp is the
	// most-significant field, big-endian).
	if a.String() >= b.String() {
		t.Errorf("expected %s < %s", a.String(), b.String())
	}
}

func TestUUIDStringRoundTrip(t *testing.T) {
	u := NewUUIDv7()
	parsed, err := parseUUID(u.String())
	if err != nil {
		t.Fatalf("parseUUID: %v", err)
	}
	if parsed != u {
		t.Errorf("round-trip mismatch: %s != %s", parsed, u)
	}
}

func TestParseUUIDRejectsGarbage(t *testing.T) {
	for _, bad := range []string{"", "not-a-uuid", "0190b3e2-1c00-7a00-8000", "zzzzzzzz-1c00-7a00-8000-000000000001"} {
		if _, err := parseUUID(bad); err == nil {
			t.Errorf("parseUUID(%q) expected error", bad)
		}
	}
}

func TestIDsParseFromCanonicalString(t *testing.T) {
	id := NewSessionID()
	got, err := ParseSessionID(id.String())
	if err != nil {
		t.Fatalf("ParseSessionID: %v", err)
	}
	if got != id {
		t.Errorf("round-trip mismatch: %s != %s", got, id)
	}
}

func TestIDMarshalIsBareString(t *testing.T) {
	id, err := ParseSessionID("0190b3e2-1d00-7a00-8000-000000000002")
	if err != nil {
		t.Fatalf("ParseSessionID: %v", err)
	}
	b, err := json.Marshal(id)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	want := `"0190b3e2-1d00-7a00-8000-000000000002"`
	if string(b) != want {
		t.Errorf("marshal = %s, want %s", b, want)
	}

	// And unmarshal from a bare string restores it.
	var back SessionID
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if back != id {
		t.Errorf("unmarshal mismatch: %s != %s", back, id)
	}
}
