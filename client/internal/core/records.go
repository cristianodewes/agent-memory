package core

import "time"

// The domain records mirror the Java records in com.agentmemory.core, field-for-field, with JSON
// tags matching the server's @JsonProperty names and @JsonPropertyOrder ordering. Nullable fields
// are pointers with `omitempty` to reproduce @JsonInclude(NON_NULL); the handoff list fields are
// plain slices WITHOUT `omitempty` so an empty handoff serializes "[]" (never null/omitted),
// matching the server which coalesces null lists to empty.
//
// These are wire/domain value shapes, not persistence entities; the store (#4) maps them to
// Postgres rows. Construction here does not re-validate scoping invariants the way the Java
// canonical constructors do — the client receives already-valid payloads from the server — but the
// JSON shape is identical, which is what the cross-language fixtures assert.

// Page mirrors com.agentmemory.core.Page: a compiled wiki page version with the page-scoped
// 3-tuple identity and an is-latest/supersedes version chain.
type Page struct {
	ID         PageID    `json:"id"`
	Identity   Identity  `json:"identity"`
	Title      string    `json:"title"`
	Body       string    `json:"body"`
	IsLatest   bool      `json:"isLatest"`
	Supersedes *PageID   `json:"supersedes,omitempty"`
	CreatedAt  time.Time `json:"createdAt"`
	UpdatedAt  time.Time `json:"updatedAt"`
}

// Observation mirrors com.agentmemory.core.Observation: one captured lifecycle event under a
// session, with project-scoped identity, the canonical kind, and the raw source-event/extension
// audit fields.
type Observation struct {
	ID          ObservationID   `json:"id"`
	SessionID   SessionID       `json:"sessionId"`
	Identity    Identity        `json:"identity"`
	Kind        ObservationKind `json:"kind"`
	SourceEvent *string         `json:"sourceEvent,omitempty"`
	Extension   *string         `json:"extension,omitempty"`
	Payload     string          `json:"payload"`
	CreatedAt   time.Time       `json:"createdAt"`
}

// Session mirrors com.agentmemory.core.Session: one agent run grouping observations, with
// project-scoped identity and an optional end time (nil while open).
type Session struct {
	ID        SessionID  `json:"id"`
	Identity  Identity   `json:"identity"`
	Agent     *string    `json:"agent,omitempty"`
	StartedAt time.Time  `json:"startedAt"`
	EndedAt   *time.Time `json:"endedAt,omitempty"`
}

// Link mirrors com.agentmemory.core.Link: a directed wikilink between page-scoped identities;
// Target is nil for a bare anchor and may name a different project (cross-project), and
// TargetResolved tracks whether a deferred/forward link has been resolved to an existing page.
type Link struct {
	ID             LinkID    `json:"id"`
	Source         Identity  `json:"source"`
	Target         *Identity `json:"target,omitempty"`
	Anchor         *string   `json:"anchor,omitempty"`
	TargetResolved bool      `json:"targetResolved"`
}

// Handoff mirrors com.agentmemory.core.Handoff: an LLM-written, single-use "where you left off"
// record with project-scoped identity. OpenQuestions/NextSteps always serialize as a JSON array
// (possibly empty), never null — hence no `omitempty`.
type Handoff struct {
	ID            HandoffID     `json:"id"`
	Identity      Identity      `json:"identity"`
	FromSession   SessionID     `json:"fromSession"`
	Status        HandoffStatus `json:"status"`
	Summary       string        `json:"summary"`
	OpenQuestions []string      `json:"openQuestions"`
	NextSteps     []string      `json:"nextSteps"`
	CreatedAt     time.Time     `json:"createdAt"`
	AcceptedAt    *time.Time    `json:"acceptedAt,omitempty"`
}
