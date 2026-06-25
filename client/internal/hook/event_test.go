package hook

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/core"
)

// aliasCase mirrors one entry of docs/contracts/fixtures/hook_aliases.json: a raw agent-native event
// spelling and the canonical kind it must normalize to. The same fixture drives the Java
// HookEventTest, so the two alias tables are proven identical.
type aliasCase struct {
	Event string `json:"event"`
	Kind  string `json:"kind"`
}

// TestAliasFixtureNormalization is the table-driven proof that every documented Claude Code / Codex /
// Cursor spelling — including the prior-art "user-prompt-submit" drop case and casing/separator
// variants — normalizes to the expected canonical kind. Reads the shared golden fixture so Go and
// Java assert the exact same mapping.
func TestAliasFixtureNormalization(t *testing.T) {
	var cases []aliasCase
	if err := json.Unmarshal(readFixture(t, "hook_aliases.json"), &cases); err != nil {
		t.Fatalf("unmarshal hook_aliases.json: %v", err)
	}
	if len(cases) == 0 {
		t.Fatal("hook_aliases.json is empty")
	}
	for _, c := range cases {
		got := ParseEvent(c.Event)
		if string(got) != c.Kind {
			t.Errorf("ParseEvent(%q) = %q, want %q", c.Event, got, c.Kind)
		}
	}
}

// TestUserPromptSubmitNotDropped pins the specific prior-art regression: the native hook spelling
// "user-prompt-submit" (and its CamelCase form) MUST resolve to user-prompt, never to other.
func TestUserPromptSubmitNotDropped(t *testing.T) {
	for _, spelling := range []string{"user-prompt-submit", "UserPromptSubmit", "user_prompt_submit"} {
		if got := ParseEvent(spelling); got != core.KindUserPrompt {
			t.Errorf("ParseEvent(%q) = %q, want %q (prior-art drop must not recur)", spelling, got, core.KindUserPrompt)
		}
	}
}

// TestParseEventFallsBackToCanonical checks resolution order: a canonical kebab token that is NOT in
// the alias table still resolves via core.ParseObservationKind, and unknown/blank input is other.
func TestParseEventFallsBackToCanonical(t *testing.T) {
	if got := ParseEvent("session-start"); got != core.KindSessionStart {
		t.Errorf("canonical token fallback: got %q, want %q", got, core.KindSessionStart)
	}
	if got := ParseEvent("   "); got != core.KindOther {
		t.Errorf("blank event: got %q, want %q", got, core.KindOther)
	}
	if got := ParseEvent("nonsense-event"); got != core.KindOther {
		t.Errorf("unknown event: got %q, want %q", got, core.KindOther)
	}
}

// TestIsRecognizedEvent distinguishes a genuine extension event from a typo.
func TestIsRecognizedEvent(t *testing.T) {
	recognized := []string{"PostToolUse", "post_tool_use", "session-end", "other", "stop"}
	for _, e := range recognized {
		if !IsRecognizedEvent(e) {
			t.Errorf("IsRecognizedEvent(%q) = false, want true", e)
		}
	}
	for _, e := range []string{"", "deploy.finished", "totally-unknown"} {
		if IsRecognizedEvent(e) {
			t.Errorf("IsRecognizedEvent(%q) = true, want false", e)
		}
	}
}

// TestEveryNonOtherKindHasAnAlias guards the alias table's completeness: every canonical kind except
// KindOther must be reachable through at least one alias entry, so no lifecycle moment is
// unmappable. (KindOther is the catch-all and intentionally has no alias.)
func TestEveryNonOtherKindHasAnAlias(t *testing.T) {
	covered := map[core.ObservationKind]bool{}
	for _, k := range Aliases() {
		covered[k] = true
	}
	for _, k := range core.ObservationKinds {
		if k == core.KindOther {
			continue
		}
		if !covered[k] {
			t.Errorf("canonical kind %q has no alias entry", k)
		}
	}
}

// --- shared helpers for the hook tests -----------------------------------------------------------

func mustWorkspace(t *testing.T) core.WorkspaceID {
	t.Helper()
	ws, err := core.NewWorkspaceID("acme")
	if err != nil {
		t.Fatalf("workspace: %v", err)
	}
	return ws
}

func mustProject(t *testing.T) core.ProjectID {
	t.Helper()
	p, err := core.NewProjectID("agent-memory")
	if err != nil {
		t.Fatalf("project: %v", err)
	}
	return p
}

func fixedTime() time.Time {
	return time.Date(2026, 6, 25, 12, 0, 0, 0, time.UTC)
}
