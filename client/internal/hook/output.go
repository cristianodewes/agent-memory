package hook

import "encoding/json"

// SessionStart hook output contract (Claude Code). A SessionStart hook adds text to the session's
// context by printing this object to stdout and exiting 0:
//
//	{"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": "<text>"}}
//
// Field names are camelCase and hookEventName is the literal "SessionStart". Printing nothing (or
// empty additionalContext) + exit 0 is a clean no-op. We emit NOTHING when there is no content, so an
// agent without a handoff sees no injected block.

// sessionStartOutput is the top-level stdout envelope for a SessionStart hook.
type sessionStartOutput struct {
	HookSpecificOutput sessionStartSpecific `json:"hookSpecificOutput"`
}

type sessionStartSpecific struct {
	HookEventName     string `json:"hookEventName"`
	AdditionalContext string `json:"additionalContext"`
}

// SessionStartAdditionalContext renders the Claude Code SessionStart stdout JSON that injects
// additionalContext into the session, or returns (nil, false) when context is blank so the caller
// emits nothing (the clean no-op). The bytes are exactly the object Claude Code parses.
func SessionStartAdditionalContext(context string) ([]byte, bool) {
	if context == "" {
		return nil, false
	}
	out := sessionStartOutput{
		HookSpecificOutput: sessionStartSpecific{
			HookEventName:     "SessionStart",
			AdditionalContext: context,
		},
	}
	b, err := json.Marshal(out)
	if err != nil {
		return nil, false // unreachable for these fields; never block session start on it
	}
	return b, true
}
