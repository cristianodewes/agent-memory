package hook

import (
	"encoding/json"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/core"
)

// Payload mirrors com.agentmemory.hooks.HookPayload: the wire envelope this client POSTs to /hook
// (and batches to /hook/batch) for a single captured lifecycle event (ARCHITECTURE §2.1, §3.1,
// §5.4). It is the INPUT shape of the capture pipeline — distinct from core.Observation, which is
// the post-ingest value the server mints (server-assigned id, sanitized payload). This struct
// carries only what a hook can know at capture time.
//
// Canonicalization: the client sends both the raw agent-native Event (e.g. "PostToolUse",
// "user-prompt-submit") AND the Kind it canonicalized that event to via ParseEvent. Keeping the raw
// Event on the wire (it becomes the observation's sourceEvent) keeps a KindOther/aliased event
// auditable and lets the alias mapping be revisited without re-capturing. The server re-derives the
// canonical kind from Event as the source of truth.
//
// Identity: hooks are project-scoped — the client reports Workspace/Project (resolved from the git
// root or a .agent-memory.toml marker, §2.1) plus the raw Cwd it resolved them from. There is no
// page coordinate at capture time.
//
// Tool fields: for tool events the client includes ToolName, ToolInput and ToolResponse. ToolInput
// and ToolResponse are json.RawMessage — NOT string — because an agent's tool input/response is
// arbitrary JSON whose shape must not be flattened. In particular ToolResponse is frequently an
// ARRAY of content blocks (the documented prior-art "Bug A": an array tool_response an object-only
// parser dropped as "no output captured"). RawMessage preserves objects, arrays and scalars
// identically so nothing is lost before sanitization (#9). This is the Go analogue of the Java
// JsonNode field; both round-trip the same fixtures.
//
// Extension seam: a third party emits an event outside the canonical set by sending its own Event
// (which canonicalizes to KindOther) together with an Extension namespace; the pair
// (Kind=other, Extension=<ns>, Event=<their name>) records the event without growing the enum (§5.4).
//
// Idempotency: ClientEventID is the client's stable per-event id. The client stamps each spooled
// event with one and re-sends it on a retried drain; the server dedupes on (sessionId, clientEventId)
// so a replay creates no duplicate observation (#8). Optional — omitted ⇒ the server always inserts.
//
// Serialization mirrors the core conventions (docs/contracts/serialization.md): lowerCamelCase
// fields, nullable fields as pointers with `omitempty` to reproduce @JsonInclude(NON_NULL), value
// types as bare scalars, timestamps as RFC-3339 Z instants. Field order matches the Java
// @JsonPropertyOrder for stable, reviewable fixtures (consumers must not depend on order).
type Payload struct {
	Event         string               `json:"event"`
	Kind          core.ObservationKind `json:"kind"`
	SessionID     core.SessionID       `json:"sessionId"`
	Workspace     core.WorkspaceID     `json:"workspace"`
	Project       core.ProjectID       `json:"project"`
	Cwd           *string              `json:"cwd,omitempty"`
	Agent         *string              `json:"agent,omitempty"`
	Title         *string              `json:"title,omitempty"`
	Body          *string              `json:"body,omitempty"`
	ToolName      *string              `json:"toolName,omitempty"`
	ToolInput     *json.RawMessage     `json:"toolInput,omitempty"`
	ToolResponse  *json.RawMessage     `json:"toolResponse,omitempty"`
	Extension     *string              `json:"extension,omitempty"`
	ClientEventID *string              `json:"clientEventId,omitempty"`
	Timestamp     time.Time            `json:"timestamp"`
}

// NewPayload builds a project-scoped hook payload, canonicalizing event to its core.ObservationKind
// via ParseEvent — the normal client-side path, so callers never resolve the kind themselves.
// Optional fields (cwd, agent, title, body, tool fields, extension) are set on the returned value.
func NewPayload(
	event string,
	sessionID core.SessionID,
	workspace core.WorkspaceID,
	project core.ProjectID,
	timestamp time.Time,
) Payload {
	return Payload{
		Event:     event,
		Kind:      ParseEvent(event),
		SessionID: sessionID,
		Workspace: workspace,
		Project:   project,
		Timestamp: timestamp,
	}
}

// IsExtensionEvent reports whether this event canonicalizes to KindOther but the client tagged it
// with an Extension namespace — i.e. a deliberate third-party event rather than an unrecognized
// spelling. Mirrors HookPayload.isExtensionEvent.
func (p Payload) IsExtensionEvent() bool {
	return p.Kind == core.KindOther && p.Extension != nil && *p.Extension != ""
}
