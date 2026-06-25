package hook

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/core"
)

func baseCtx() InputContext {
	return InputContext{
		Workspace: "acme",
		Project:   "agent-memory",
		Cwd:       "/home/dev/src/agent-memory",
		Now:       time.Date(2026, 6, 25, 12, 0, 0, 0, time.UTC),
	}
}

func TestBuildPayloadCanonicalizesEventAndStampsClientEventID(t *testing.T) {
	raw := []byte(`{"hook_event_name":"UserPromptSubmit","prompt":"why is recall slow?"}`)
	p, err := BuildPayload(raw, baseCtx())
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	if p.Kind != core.KindUserPrompt {
		t.Errorf("kind = %q, want user-prompt", p.Kind)
	}
	if p.Event != "UserPromptSubmit" {
		t.Errorf("raw event must be preserved, got %q", p.Event)
	}
	if p.Body == nil || *p.Body != "why is recall slow?" {
		t.Errorf("body = %v, want the prompt text", p.Body)
	}
	if p.ClientEventID == nil || *p.ClientEventID == "" {
		t.Error("a ClientEventID must be stamped for idempotent replay")
	}
}

func TestBuildPayloadEventFlagOverridesJSON(t *testing.T) {
	raw := []byte(`{"hook_event_name":"whatever"}`)
	ctx := baseCtx()
	ctx.Event = "SessionEnd"
	p, err := BuildPayload(raw, ctx)
	if err != nil {
		t.Fatal(err)
	}
	if p.Kind != core.KindSessionEnd {
		t.Fatalf("the --event flag must win: got %q", p.Kind)
	}
}

// TestBuildPayloadPreservesArrayToolResponse is the prior-art "Bug A" guard on the client side: an
// array tool_response must be carried verbatim as a JSON array, never flattened or dropped.
func TestBuildPayloadPreservesArrayToolResponse(t *testing.T) {
	raw := []byte(`{
		"hook_event_name":"PostToolUse",
		"tool_name":"Read",
		"tool_input":{"file_path":"/README.md"},
		"tool_response":[{"type":"text","text":"# agent-memory\n"},{"type":"text","text":"more"}]
	}`)
	p, err := BuildPayload(raw, baseCtx())
	if err != nil {
		t.Fatal(err)
	}
	if p.Kind != core.KindPostToolUse {
		t.Fatalf("kind = %q, want post-tool-use", p.Kind)
	}
	if p.ToolResponse == nil {
		t.Fatal("toolResponse was dropped (Bug A)")
	}
	var tree any
	if err := json.Unmarshal(*p.ToolResponse, &tree); err != nil {
		t.Fatalf("toolResponse is not valid JSON: %v", err)
	}
	arr, ok := tree.([]any)
	if !ok || len(arr) != 2 {
		t.Fatalf("toolResponse must be a 2-element array, got %T", tree)
	}
}

func TestBuildPayloadExtensionEvent(t *testing.T) {
	raw := []byte(`{"hook_event_name":"deploy.finished","extension":"vendor.deploy","body":"ok"}`)
	p, err := BuildPayload(raw, baseCtx())
	if err != nil {
		t.Fatal(err)
	}
	if p.Kind != core.KindOther {
		t.Errorf("unknown event must canonicalize to other, got %q", p.Kind)
	}
	if !p.IsExtensionEvent() {
		t.Error("kind=other + extension must be flagged as an extension event")
	}
}

func TestBuildPayloadEmptyStdinStillValidWithEventFlag(t *testing.T) {
	// A bare hook with no stdin payload (e.g. Stop) is valid as long as --event is given.
	ctx := baseCtx()
	ctx.Event = "Stop"
	p, err := BuildPayload(nil, ctx)
	if err != nil {
		t.Fatalf("empty payload with --event should be valid: %v", err)
	}
	if p.Kind != core.KindStop {
		t.Fatalf("kind = %q, want stop", p.Kind)
	}
}

func TestBuildPayloadRejectsMalformedJSON(t *testing.T) {
	_, err := BuildPayload([]byte("{not json"), baseCtx())
	if err == nil {
		t.Fatal("expected an error for malformed agent JSON")
	}
}

func TestBuildPayloadRejectsMissingEvent(t *testing.T) {
	_, err := BuildPayload([]byte(`{"prompt":"hi"}`), baseCtx())
	if err == nil {
		t.Fatal("expected an error when no event name is available")
	}
}

func TestBuildPayloadReusesAgentClientEventID(t *testing.T) {
	raw := []byte(`{"hook_event_name":"Stop","client_event_id":"agent-supplied-id"}`)
	p, err := BuildPayload(raw, baseCtx())
	if err != nil {
		t.Fatal(err)
	}
	if p.ClientEventID == nil || *p.ClientEventID != "agent-supplied-id" {
		t.Fatalf("agent-supplied client_event_id must be used, got %v", p.ClientEventID)
	}
}
