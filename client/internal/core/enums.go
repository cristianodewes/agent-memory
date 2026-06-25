package core

import (
	"encoding/json"
	"fmt"
	"strings"
)

// ObservationKind is the canonical set of agent lifecycle event kinds, mirroring
// com.agentmemory.core.ObservationKind. The client canonicalizes its agent-native hook names down
// to these before sending them (the alias mapping itself is issue #7). The wire form is the
// kebab-case token; unknown tokens parse leniently to KindOther so a newer client never breaks
// ingest.
type ObservationKind string

const (
	KindSessionStart ObservationKind = "session-start"
	KindUserPrompt   ObservationKind = "user-prompt"
	KindPreToolUse   ObservationKind = "pre-tool-use"
	KindPostToolUse  ObservationKind = "post-tool-use"
	KindPreCompact   ObservationKind = "pre-compact"
	KindNotification ObservationKind = "notification"
	KindStop         ObservationKind = "stop"
	KindSessionEnd   ObservationKind = "session-end"
	KindOther        ObservationKind = "other"
)

// ObservationKinds lists the canonical kinds in declaration order. Used by tests to lock this set
// against the server enum and the docs/contracts/fixtures/observation_kinds.json fixture.
var ObservationKinds = []ObservationKind{
	KindSessionStart, KindUserPrompt, KindPreToolUse, KindPostToolUse,
	KindPreCompact, KindNotification, KindStop, KindSessionEnd, KindOther,
}

// ParseObservationKind canonicalizes a wire token (case-insensitive, '_' treated as '-'),
// returning KindOther for blank or unrecognized input — the lenient policy the server applies.
func ParseObservationKind(token string) ObservationKind {
	if strings.TrimSpace(token) == "" {
		return KindOther
	}
	normalized := ObservationKind(strings.ReplaceAll(strings.ToLower(strings.TrimSpace(token)), "_", "-"))
	for _, k := range ObservationKinds {
		if k == normalized {
			return k
		}
	}
	return KindOther
}

// MarshalJSON writes the kebab-case wire token.
func (k ObservationKind) MarshalJSON() ([]byte, error) {
	return json.Marshal(string(k))
}

// UnmarshalJSON parses leniently via ParseObservationKind (unknown -> KindOther).
func (k *ObservationKind) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return fmt.Errorf("core: observation kind must be a JSON string: %w", err)
	}
	*k = ParseObservationKind(s)
	return nil
}

// HandoffStatus is the lifecycle state of a Handoff (open/accepted/expired), mirroring
// com.agentmemory.core.HandoffStatus. Parsing is strict — an unknown status is a data error — so
// UnmarshalJSON fails on an unrecognized token.
type HandoffStatus string

const (
	HandoffOpen     HandoffStatus = "open"
	HandoffAccepted HandoffStatus = "accepted"
	HandoffExpired  HandoffStatus = "expired"
)

var handoffStatuses = []HandoffStatus{HandoffOpen, HandoffAccepted, HandoffExpired}

// ParseHandoffStatus parses a wire token strictly (case-insensitive), erroring on blank/unknown.
func ParseHandoffStatus(token string) (HandoffStatus, error) {
	if strings.TrimSpace(token) == "" {
		return "", fmt.Errorf("core: handoff status must not be blank")
	}
	normalized := HandoffStatus(strings.ToLower(strings.TrimSpace(token)))
	for _, s := range handoffStatuses {
		if s == normalized {
			return s, nil
		}
	}
	return "", fmt.Errorf("core: unknown handoff status: %q", token)
}

// MarshalJSON writes the lowercase wire token.
func (s HandoffStatus) MarshalJSON() ([]byte, error) {
	return json.Marshal(string(s))
}

// UnmarshalJSON parses strictly via ParseHandoffStatus.
func (s *HandoffStatus) UnmarshalJSON(b []byte) error {
	var raw string
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("core: handoff status must be a JSON string: %w", err)
	}
	parsed, err := ParseHandoffStatus(raw)
	if err != nil {
		return err
	}
	*s = parsed
	return nil
}
