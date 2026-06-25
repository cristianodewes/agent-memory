package core

import (
	"encoding/json"
	"fmt"
)

// The surrogate id types each wrap a UUID in a distinct Go type so a SessionID can never be passed
// where an ObservationID is expected — the same typing guarantee the Java records give. All are
// minted as UUIDv7 and serialize as the canonical lowercase UUID string (a bare JSON scalar),
// matching the @JsonValue/@JsonCreator behavior of the Java id records.

// SessionID identifies a capture Session. Mirrors com.agentmemory.core.SessionId.
type SessionID struct{ uuid UUID }

// ObservationID identifies a single Observation. Mirrors com.agentmemory.core.ObservationId.
type ObservationID struct{ uuid UUID }

// PageID is a surrogate key for one version of a Page. Mirrors com.agentmemory.core.PageId.
type PageID struct{ uuid UUID }

// LinkID is a surrogate key for a Link row. Mirrors com.agentmemory.core.LinkId.
type LinkID struct{ uuid UUID }

// HandoffID is a surrogate key for a Handoff row. Mirrors com.agentmemory.core.HandoffId.
type HandoffID struct{ uuid UUID }

// NewSessionID mints a fresh time-ordered session id.
func NewSessionID() SessionID { return SessionID{NewUUIDv7()} }

// NewObservationID mints a fresh time-ordered observation id.
func NewObservationID() ObservationID { return ObservationID{NewUUIDv7()} }

// NewPageID mints a fresh time-ordered page-version id.
func NewPageID() PageID { return PageID{NewUUIDv7()} }

// NewLinkID mints a fresh time-ordered link id.
func NewLinkID() LinkID { return LinkID{NewUUIDv7()} }

// NewHandoffID mints a fresh time-ordered handoff id.
func NewHandoffID() HandoffID { return HandoffID{NewUUIDv7()} }

// ParseSessionID parses the canonical UUID string into a SessionID.
func ParseSessionID(s string) (SessionID, error) { u, err := parseUUID(s); return SessionID{u}, err }

// ParseObservationID parses the canonical UUID string into an ObservationID.
func ParseObservationID(s string) (ObservationID, error) {
	u, err := parseUUID(s)
	return ObservationID{u}, err
}

// ParsePageID parses the canonical UUID string into a PageID.
func ParsePageID(s string) (PageID, error) { u, err := parseUUID(s); return PageID{u}, err }

// ParseLinkID parses the canonical UUID string into a LinkID.
func ParseLinkID(s string) (LinkID, error) { u, err := parseUUID(s); return LinkID{u}, err }

// ParseHandoffID parses the canonical UUID string into a HandoffID.
func ParseHandoffID(s string) (HandoffID, error) { u, err := parseUUID(s); return HandoffID{u}, err }

// UUID exposes the underlying UUID (e.g. for ordering or storage).
func (id SessionID) UUID() UUID     { return id.uuid }
func (id ObservationID) UUID() UUID { return id.uuid }
func (id PageID) UUID() UUID        { return id.uuid }
func (id LinkID) UUID() UUID        { return id.uuid }
func (id HandoffID) UUID() UUID     { return id.uuid }

func (id SessionID) String() string     { return id.uuid.String() }
func (id ObservationID) String() string { return id.uuid.String() }
func (id PageID) String() string        { return id.uuid.String() }
func (id LinkID) String() string        { return id.uuid.String() }
func (id HandoffID) String() string     { return id.uuid.String() }

// --- JSON: bare canonical UUID string on the wire -------------------------------------------------

func (id SessionID) MarshalJSON() ([]byte, error)     { return marshalUUID(id.uuid) }
func (id ObservationID) MarshalJSON() ([]byte, error) { return marshalUUID(id.uuid) }
func (id PageID) MarshalJSON() ([]byte, error)        { return marshalUUID(id.uuid) }
func (id LinkID) MarshalJSON() ([]byte, error)        { return marshalUUID(id.uuid) }
func (id HandoffID) MarshalJSON() ([]byte, error)     { return marshalUUID(id.uuid) }

func (id *SessionID) UnmarshalJSON(b []byte) error { return unmarshalUUID(b, &id.uuid, "session id") }
func (id *ObservationID) UnmarshalJSON(b []byte) error {
	return unmarshalUUID(b, &id.uuid, "observation id")
}
func (id *PageID) UnmarshalJSON(b []byte) error    { return unmarshalUUID(b, &id.uuid, "page id") }
func (id *LinkID) UnmarshalJSON(b []byte) error    { return unmarshalUUID(b, &id.uuid, "link id") }
func (id *HandoffID) UnmarshalJSON(b []byte) error { return unmarshalUUID(b, &id.uuid, "handoff id") }

func marshalUUID(u UUID) ([]byte, error) {
	return json.Marshal(u.String())
}

func unmarshalUUID(b []byte, dst *UUID, label string) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return fmt.Errorf("core: %s must be a JSON string: %w", label, err)
	}
	u, err := parseUUID(s)
	if err != nil {
		return err
	}
	*dst = u
	return nil
}
