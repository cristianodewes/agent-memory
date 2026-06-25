package hook

import (
	"regexp"
	"strings"

	"github.com/cristianodewes/agent-memory/client/internal/core"
)

// Event canonicalization mirrors com.agentmemory.hooks.HookEvent. Different agents spell the same
// lifecycle moment differently — Claude Code emits "PostToolUse", a shell hook might emit
// "post-tool-use" or "post_tool_use", Codex/Cursor use their own names — and EVERY recognized
// spelling must resolve to one canonical core.ObservationKind so no real hook is silently dropped.
//
// This is the fix for a documented prior-art bug: a native hook sent "user-prompt-submit" and the
// server, matching only an exact literal, dropped the prompt. Here that spelling — and every other
// documented one — is in the alias table and maps to core.KindUserPrompt.

// aliases maps documented agent-native event spellings to their canonical kind. Keys are stored in
// the normalized form produced by normalizeKey (lower-case, '_'/whitespace runs folded to '-'), so
// one entry covers every casing of a name. The canonical kebab tokens themselves are handled by the
// core.ParseObservationKind fallback in ParseEvent and are intentionally not duplicated here.
//
// Sources: Claude Code lifecycle hook names; Codex / Cursor / generic agent spellings
// (Survey §2.1, §3.4). Kept in lockstep with the Java HookEvent.ALIASES table.
var aliases = map[string]core.ObservationKind{
	// session start
	"sessionstart":    core.KindSessionStart,
	"session-started": core.KindSessionStart,
	"startup":         core.KindSessionStart,
	"start":           core.KindSessionStart,

	// user prompt (the prior-art "user-prompt-submit" drop lives here)
	"userpromptsubmit":   core.KindUserPrompt,
	"user-prompt-submit": core.KindUserPrompt,
	"prompt":             core.KindUserPrompt,
	"user-message":       core.KindUserPrompt,
	"usermessage":        core.KindUserPrompt,

	// pre tool use
	"pretooluse":      core.KindPreToolUse,
	"before-tool-use": core.KindPreToolUse,
	"tool-call-start": core.KindPreToolUse,
	"toolcallstart":   core.KindPreToolUse,

	// post tool use (model the array tool_response shape; prior-art Bug A)
	"posttooluse":    core.KindPostToolUse,
	"after-tool-use": core.KindPostToolUse,
	"tool-call-end":  core.KindPostToolUse,
	"toolcallend":    core.KindPostToolUse,
	"tool-result":    core.KindPostToolUse,
	"toolresult":     core.KindPostToolUse,

	// pre compact
	"precompact":     core.KindPreCompact,
	"compact":        core.KindPreCompact,
	"before-compact": core.KindPreCompact,

	// notification
	"notify": core.KindNotification,

	// stop
	"stophook":      core.KindStop,
	"subagentstop":  core.KindStop,
	"subagent-stop": core.KindStop,
	"turn-end":      core.KindStop,
	"turnend":       core.KindStop,

	// session end
	"sessionend":      core.KindSessionEnd,
	"session-stopped": core.KindSessionEnd,
	"shutdown":        core.KindSessionEnd,
	"exit":            core.KindSessionEnd,
}

// keySeparators collapses runs of whitespace and underscores to a single '-' so "Post_Tool_Use",
// "post tool use" and "POST-TOOL-USE" all normalize to "post-tool-use".
var keySeparators = regexp.MustCompile(`[\s_]+`)

// ParseEvent resolves an agent-native event name to its canonical core.ObservationKind.
//
// Matching is forgiving — case, and '_' / '-' / whitespace separators are all equivalent — because
// clients are inconsistent and we would rather over-match a known event than drop a real one.
// Unrecognized or blank input resolves to core.KindOther (never panics), which the caller may pair
// with an extension namespace. Resolution order:
//  1. the alias table (normalized key);
//  2. core.ParseObservationKind, so a client already sending the canonical kebab token resolves;
//  3. core.KindOther for anything unknown.
func ParseEvent(event string) core.ObservationKind {
	if strings.TrimSpace(event) == "" {
		return core.KindOther
	}
	if k, ok := aliases[normalizeKey(event)]; ok {
		return k
	}
	// Fall back to the canonical parser so a client already sending the kebab token (or a future
	// kind this build does not alias) still resolves; that parser yields KindOther for the unknown.
	return core.ParseObservationKind(event)
}

// IsRecognizedEvent reports whether event is a spelling this build explicitly recognizes — it
// appears in the alias table or is a canonical kind other than core.KindOther. Useful for deciding
// whether an incoming KindOther is a genuine extension event versus a typo. The literal "other"
// counts as recognized. Mirrors HookEvent.isRecognized.
func IsRecognizedEvent(event string) bool {
	if strings.TrimSpace(event) == "" {
		return false
	}
	key := normalizeKey(event)
	if _, ok := aliases[key]; ok {
		return true
	}
	// Canonical kebab tokens (including the literal "other") resolve via ParseObservationKind; an
	// unknown token is the only case it coerces to KindOther, so distinguish that here.
	return core.ParseObservationKind(event) != core.KindOther || key == "other"
}

// normalizeKey is the normal form for an alias key / lookup token: trimmed, lower-cased, with '_'
// and whitespace runs folded to '-'. Mirrors HookEvent.normalizeKey.
func normalizeKey(raw string) string {
	return keySeparators.ReplaceAllString(strings.ToLower(strings.TrimSpace(raw)), "-")
}

// Aliases returns a copy of the alias table (normalized spelling -> canonical kind). The canonical
// kebab tokens handled by the core.ParseObservationKind fallback are not included. Exposed for
// tests and documentation.
func Aliases() map[string]core.ObservationKind {
	out := make(map[string]core.ObservationKind, len(aliases))
	for k, v := range aliases {
		out[k] = v
	}
	return out
}
