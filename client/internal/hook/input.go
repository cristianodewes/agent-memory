package hook

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/core"
)

// agentInput is the permissive shape of an agent's native hook JSON (Claude Code and compatible
// agents write this to the hook's stdin). Every field is optional and snake_case to match the
// agent's wire format; unknown fields are ignored. The client maps this onto the canonical
// hook.Payload. Tool input/response are kept as raw JSON so an array tool_response survives intact
// (prior-art "Bug A").
type agentInput struct {
	HookEventName string          `json:"hook_event_name"`
	SessionID     string          `json:"session_id"`
	Cwd           string          `json:"cwd"`
	Workspace     string          `json:"workspace"`
	Project       string          `json:"project"`
	Agent         string          `json:"agent"`
	Prompt        string          `json:"prompt"`
	Message       string          `json:"message"`
	Title         string          `json:"title"`
	Body          string          `json:"body"`
	ToolName      string          `json:"tool_name"`
	ToolInput     json.RawMessage `json:"tool_input"`
	ToolResponse  json.RawMessage `json:"tool_response"`
	Extension     string          `json:"extension"`
	ClientEventID string          `json:"client_event_id"`
}

// InputContext supplies the bits the hook command knows independently of the agent JSON: the
// canonical event name from --event (authoritative over the JSON's hook_event_name when set), the
// resolved identity (workspace/project/cwd), and the capture time. Identity resolution lives in the
// command (it walks the filesystem); this keeps BuildPayload pure and unit-testable.
type InputContext struct {
	// Event is the --event flag value; if empty, the JSON's hook_event_name is used.
	Event string
	// Workspace/Project are the resolved identity coordinates; if empty, the JSON's values are used.
	Workspace string
	Project   string
	// Cwd is the resolved working directory; if empty, the JSON's cwd is used.
	Cwd string
	// Now is the capture timestamp (injectable for deterministic tests).
	Now time.Time
}

// BuildPayload parses the agent's native hook JSON and assembles a canonical hook.Payload, ready to
// spool. It canonicalizes the event name to an ObservationKind via ParseEvent (#7), preserves the
// raw event as sourceEvent material, stamps a stable ClientEventID (minted if the agent did not
// supply one) so a retried drain dedupes server-side, and keeps tool_input/tool_response as raw JSON.
//
// rawJSON may be empty (a hook with no stdin payload, e.g. a bare Stop): the event still produces a
// valid payload carrying just identity + kind + timestamp. A non-empty rawJSON that is not valid
// JSON is an error (the caller decides whether to still spool a minimal event).
func BuildPayload(rawJSON []byte, ctx InputContext) (Payload, error) {
	var in agentInput
	if len(strings.TrimSpace(string(rawJSON))) > 0 {
		if err := json.Unmarshal(rawJSON, &in); err != nil {
			return Payload{}, fmt.Errorf("hook: parse agent input JSON: %w", err)
		}
	}

	event := firstNonEmpty(ctx.Event, in.HookEventName)
	if strings.TrimSpace(event) == "" {
		return Payload{}, fmt.Errorf("hook: no event name (pass --event or include hook_event_name)")
	}

	wsRaw := firstNonEmpty(ctx.Workspace, in.Workspace)
	projRaw := firstNonEmpty(ctx.Project, in.Project)
	ws, err := core.NewWorkspaceID(wsRaw)
	if err != nil {
		return Payload{}, fmt.Errorf("hook: workspace: %w", err)
	}
	proj, err := core.NewProjectID(projRaw)
	if err != nil {
		return Payload{}, fmt.Errorf("hook: project: %w", err)
	}

	now := ctx.Now
	if now.IsZero() {
		now = time.Now().UTC()
	}

	p := NewPayload(event, sessionIDOrNew(in.SessionID), ws, proj, now.UTC())

	if cwd := firstNonEmpty(ctx.Cwd, in.Cwd); cwd != "" {
		p.Cwd = &cwd
	}
	if in.Agent != "" {
		p.Agent = &in.Agent
	}
	if in.Title != "" {
		title := in.Title
		p.Title = &title
	}
	// Body: prefer an explicit body, then the prompt, then a generic message.
	if body := firstNonEmpty(in.Body, in.Prompt, in.Message); body != "" {
		p.Body = &body
	}
	if in.ToolName != "" {
		p.ToolName = &in.ToolName
	}
	if raw := rawOrNil(in.ToolInput); raw != nil {
		p.ToolInput = raw
	}
	if raw := rawOrNil(in.ToolResponse); raw != nil {
		p.ToolResponse = raw
	}
	if in.Extension != "" {
		p.Extension = &in.Extension
	}

	// ClientEventID: use the agent's if it supplied one (so its own retries dedupe), else mint a
	// stable UUIDv7 now so a re-drain of this spooled event dedupes server-side (#8).
	cid := strings.TrimSpace(in.ClientEventID)
	if cid == "" {
		cid = core.NewUUIDv7().String()
	}
	p.ClientEventID = &cid

	return p, nil
}

// sessionIDOrNew parses the agent's session id, or mints a fresh one when absent/invalid so the
// event is always attributable to some session (the server groups by it).
func sessionIDOrNew(raw string) core.SessionID {
	if id, err := core.ParseSessionID(strings.TrimSpace(raw)); err == nil {
		return id
	}
	return core.NewSessionID()
}

// rawOrNil returns a *json.RawMessage for a non-empty, non-"null" raw value, else nil so the field
// is omitted.
func rawOrNil(raw json.RawMessage) *json.RawMessage {
	s := strings.TrimSpace(string(raw))
	if s == "" || s == "null" {
		return nil
	}
	cp := make(json.RawMessage, len(raw))
	copy(cp, raw)
	return &cp
}

// firstNonEmpty returns the first argument that is not blank after trimming.
func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
