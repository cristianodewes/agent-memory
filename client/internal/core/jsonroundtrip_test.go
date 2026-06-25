package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// Cross-language golden-fixture round-trip on the Go side: each fixture under
// docs/contracts/fixtures/ is unmarshaled into its typed core struct, re-marshaled, and the result
// is asserted semantically equal to the original fixture. The identical files are round-tripped by
// the Java JsonRoundTripTest; both suites green ⇒ Go and Java agree on the wire contract (issue #3).
//
// Comparison is semantic: both JSON blobs are decoded into generic any trees (maps are
// order-independent) and compared with reflect.DeepEqual, the Go analogue of the Java side's
// JsonNode equality. Field names, casing, types and null-omission must match — that is the point.

// fixturesDir locates docs/contracts/fixtures/ relative to this package (client/internal/core).
func fixturesDir(t *testing.T) string {
	t.Helper()
	// up from internal/core -> internal -> client -> repo root
	dir, err := filepath.Abs(filepath.Join("..", "..", "..", "docs", "contracts", "fixtures"))
	if err != nil {
		t.Fatalf("resolve fixtures dir: %v", err)
	}
	if _, err := os.Stat(dir); err != nil {
		t.Fatalf("fixtures dir %s not found: %v", dir, err)
	}
	return dir
}

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(fixturesDir(t), name))
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return b
}

// decodeTree decodes raw JSON into a generic, order-independent tree for semantic comparison.
func decodeTree(t *testing.T, raw []byte) any {
	t.Helper()
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatalf("decode tree: %v\nraw: %s", err, raw)
	}
	return v
}

// assertRoundTrips unmarshals the fixture into *dst, re-marshals it, and asserts the re-marshaled
// JSON is semantically equal to the original fixture.
func assertRoundTrips(t *testing.T, fixtureName string, dst any) {
	t.Helper()
	original := readFixture(t, fixtureName)
	if err := json.Unmarshal(original, dst); err != nil {
		t.Fatalf("unmarshal %s into %T: %v", fixtureName, dst, err)
	}
	reserialized, err := json.Marshal(dst)
	if err != nil {
		t.Fatalf("marshal %T: %v", dst, err)
	}
	want := decodeTree(t, original)
	got := decodeTree(t, reserialized)
	if !reflect.DeepEqual(want, got) {
		t.Fatalf("round-trip of %s differs:\n want: %s\n  got: %s", fixtureName, original, reserialized)
	}
}

func TestIdentityPageRoundTrips(t *testing.T) { assertRoundTrips(t, "identity_page.json", &Identity{}) }
func TestIdentityProjectRoundTrips(t *testing.T) {
	assertRoundTrips(t, "identity_project.json", &Identity{})
}
func TestPageRoundTrips(t *testing.T)        { assertRoundTrips(t, "page.json", &Page{}) }
func TestObservationRoundTrips(t *testing.T) { assertRoundTrips(t, "observation.json", &Observation{}) }
func TestObservationMinimalRoundTrips(t *testing.T) {
	assertRoundTrips(t, "observation_minimal.json", &Observation{})
}
func TestSessionRoundTrips(t *testing.T)      { assertRoundTrips(t, "session.json", &Session{}) }
func TestSessionOpenRoundTrips(t *testing.T)  { assertRoundTrips(t, "session_open.json", &Session{}) }
func TestLinkRoundTrips(t *testing.T)         { assertRoundTrips(t, "link.json", &Link{}) }
func TestLinkDeferredRoundTrips(t *testing.T) { assertRoundTrips(t, "link_deferred.json", &Link{}) }
func TestHandoffRoundTrips(t *testing.T)      { assertRoundTrips(t, "handoff.json", &Handoff{}) }
func TestHandoffOpenRoundTrips(t *testing.T)  { assertRoundTrips(t, "handoff_open.json", &Handoff{}) }

func TestObservationKindsFixtureMatchesEnum(t *testing.T) {
	var tokens []string
	if err := json.Unmarshal(readFixture(t, "observation_kinds.json"), &tokens); err != nil {
		t.Fatalf("unmarshal kinds: %v", err)
	}
	if len(tokens) != len(ObservationKinds) {
		t.Fatalf("kind count mismatch: fixture %d vs enum %d", len(tokens), len(ObservationKinds))
	}
	for i, tok := range tokens {
		if ObservationKind(tok) != ObservationKinds[i] {
			t.Errorf("kind[%d]: fixture %q != enum %q", i, tok, ObservationKinds[i])
		}
		if ParseObservationKind(tok) != ObservationKinds[i] {
			t.Errorf("ParseObservationKind(%q) != %q", tok, ObservationKinds[i])
		}
	}
}

// TestHandoffEmptyListsSerializeAsArray guards the no-`omitempty` decision: an open handoff with
// empty lists must emit "openQuestions":[] / "nextSteps":[], never null or omitted, to match the
// server. (The fixture round-trip already covers this, but this asserts the marshaled bytes.)
func TestHandoffEmptyListsSerializeAsArray(t *testing.T) {
	var h Handoff
	if err := json.Unmarshal(readFixture(t, "handoff_open.json"), &h); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	b, err := json.Marshal(h)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	s := string(b)
	for _, want := range []string{`"openQuestions":[]`, `"nextSteps":[]`} {
		if !strings.Contains(s, want) {
			t.Errorf("expected %s in %s", want, s)
		}
	}
}
