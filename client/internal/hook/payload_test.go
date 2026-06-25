package hook

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/core"
)

// Cross-language golden-fixture round-trip on the Go side for the hook wire envelope (issue #7).
// Each docs/contracts/fixtures/hook_*.json fixture is unmarshaled into a Payload, re-marshaled, and
// asserted semantically equal to the original. The identical files are round-tripped by the Java
// HookPayloadJsonRoundTripTest; both green ⇒ Go and Java agree on the hook payload contract.
//
// Comparison is semantic (order-independent generic-tree DeepEqual), the same discipline as the #3
// core round-trip suite (jsonroundtrip_test.go), so object key order and insignificant whitespace
// in the hand-edited fixtures do not matter — field names, casing, types and null-omission do.

func fixturesDir(t *testing.T) string {
	t.Helper()
	// up from client/internal/hook -> internal -> client -> repo root
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

func decodeTree(t *testing.T, raw []byte) any {
	t.Helper()
	var v any
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatalf("decode tree: %v\nraw: %s", err, raw)
	}
	return v
}

// assertPayloadRoundTrips unmarshals the fixture into a Payload, re-marshals it, and asserts the
// result is semantically equal to the original fixture.
func assertPayloadRoundTrips(t *testing.T, fixtureName string) {
	t.Helper()
	original := readFixture(t, fixtureName)
	var p Payload
	if err := json.Unmarshal(original, &p); err != nil {
		t.Fatalf("unmarshal %s into Payload: %v", fixtureName, err)
	}
	reserialized, err := json.Marshal(p)
	if err != nil {
		t.Fatalf("marshal Payload from %s: %v", fixtureName, err)
	}
	want := decodeTree(t, original)
	got := decodeTree(t, reserialized)
	if !reflect.DeepEqual(want, got) {
		t.Fatalf("round-trip of %s differs:\n want: %s\n  got: %s", fixtureName, original, reserialized)
	}
}

// hookFixtures is every committed hook-payload fixture. Adding a fixture here locks it into both the
// Go and (by mirrored list) Java round-trip suites.
var hookFixtures = []string{
	"hook_session_start.json",
	"hook_user_prompt.json",
	"hook_pre_tool_use.json",
	"hook_post_tool_use.json",
	"hook_post_tool_use_array.json",
	"hook_pre_compact.json",
	"hook_notification.json",
	"hook_stop.json",
	"hook_session_end.json",
	"hook_extension.json",
	"hook_minimal.json",
	"hook_idempotent.json",
}

func TestHookPayloadFixturesRoundTrip(t *testing.T) {
	for _, name := range hookFixtures {
		t.Run(name, func(t *testing.T) { assertPayloadRoundTrips(t, name) })
	}
}

// TestClientEventIDRoundTrips pins the #8 idempotency-key field: it survives the round-trip and is
// surfaced as a non-nil pointer when present in the payload.
func TestClientEventIDRoundTrips(t *testing.T) {
	var p Payload
	if err := json.Unmarshal(readFixture(t, "hook_idempotent.json"), &p); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if p.ClientEventID == nil || *p.ClientEventID != "spool-000123" {
		t.Fatalf("clientEventId = %v, want \"spool-000123\"", p.ClientEventID)
	}
}

// TestPostToolUseArrayResponsePreserved is the explicit guard for the prior-art "Bug A": an array
// tool_response must survive the round-trip as a JSON array, not be dropped or coerced to an object.
func TestPostToolUseArrayResponsePreserved(t *testing.T) {
	var p Payload
	if err := json.Unmarshal(readFixture(t, "hook_post_tool_use_array.json"), &p); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if p.ToolResponse == nil {
		t.Fatal("toolResponse was dropped (Bug A): got nil")
	}
	var tree any
	if err := json.Unmarshal(*p.ToolResponse, &tree); err != nil {
		t.Fatalf("toolResponse is not valid JSON: %v", err)
	}
	arr, ok := tree.([]any)
	if !ok {
		t.Fatalf("toolResponse must round-trip as a JSON array, got %T", tree)
	}
	if len(arr) != 2 {
		t.Fatalf("expected 2 content blocks, got %d", len(arr))
	}
}

// TestHookExtensionEventDetected checks the extension seam: a non-canonical event with an extension
// namespace deserializes to KindOther, preserves the raw event as sourceEvent material, and is
// flagged as an extension event.
func TestHookExtensionEventDetected(t *testing.T) {
	var p Payload
	if err := json.Unmarshal(readFixture(t, "hook_extension.json"), &p); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if p.Kind != core.KindOther {
		t.Errorf("extension event kind = %q, want %q", p.Kind, core.KindOther)
	}
	if p.Event != "deploy.finished" {
		t.Errorf("raw event = %q, want preserved %q", p.Event, "deploy.finished")
	}
	if !p.IsExtensionEvent() {
		t.Error("IsExtensionEvent() = false, want true for kind=other + extension")
	}
}

// TestNewPayloadCanonicalizesEvent checks the constructor canonicalizes a raw agent-native event to
// its kind without the caller resolving it.
func TestNewPayloadCanonicalizesEvent(t *testing.T) {
	p := NewPayload("UserPromptSubmit", core.NewSessionID(), mustWorkspace(t), mustProject(t), fixedTime())
	if p.Kind != core.KindUserPrompt {
		t.Errorf("NewPayload kind = %q, want %q", p.Kind, core.KindUserPrompt)
	}
	if p.IsExtensionEvent() {
		t.Error("a recognized event must not be flagged as an extension event")
	}
}
