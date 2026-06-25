package hook

import "encoding/json"

// Claude Code "additionalContext" hook output contract. A hook adds text to the session/turn context
// by printing this object to stdout and exiting 0:
//
//	{"hookSpecificOutput": {"hookEventName": "<Event>", "additionalContext": "<text>"}}
//
// Field names are camelCase and hookEventName is the literal event name — "SessionStart" (#23, the
// handoff "where you left off" block) or "UserPromptSubmit" (#84, the proactive recall block). Printing
// nothing (or empty additionalContext) + exit 0 is a clean no-op, so we emit NOTHING when there is no
// content (no handoff, or a low-signal prompt).

// additionalContextOutput is the top-level stdout envelope for an additionalContext hook.
type additionalContextOutput struct {
	HookSpecificOutput additionalContextSpecific `json:"hookSpecificOutput"`
}

type additionalContextSpecific struct {
	HookEventName     string `json:"hookEventName"`
	AdditionalContext string `json:"additionalContext"`
}

// additionalContext renders the Claude Code stdout JSON that injects additionalContext for the given
// hookEventName, or (nil, false) when context is blank so the caller emits nothing (the clean no-op).
// The returned bytes are exactly the object Claude Code parses.
func additionalContext(hookEventName, context string) ([]byte, bool) {
	if context == "" {
		return nil, false
	}
	out := additionalContextOutput{
		HookSpecificOutput: additionalContextSpecific{
			HookEventName:     hookEventName,
			AdditionalContext: context,
		},
	}
	b, err := json.Marshal(out)
	if err != nil {
		return nil, false // unreachable for these fields; never block the hook on it
	}
	return b, true
}

// SessionStartAdditionalContext renders the SessionStart additionalContext stdout JSON (issue #23), or
// (nil, false) when context is blank so an agent without a handoff sees no injected block.
func SessionStartAdditionalContext(context string) ([]byte, bool) {
	return additionalContext("SessionStart", context)
}

// UserPromptSubmitAdditionalContext renders the UserPromptSubmit additionalContext stdout JSON (issue
// #84: proactive recall injection), or (nil, false) when context is blank so a low-signal prompt
// injects nothing.
func UserPromptSubmitAdditionalContext(context string) ([]byte, bool) {
	return additionalContext("UserPromptSubmit", context)
}
