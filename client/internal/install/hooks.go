package install

import (
	"bytes"
	"strings"
)

// ManagedHookEvents are the seven Claude Code lifecycle events agent-memory wires its capture hook onto,
// mirroring ai-memory's CLAUDE_CODE_EVENTS (crates/ai-memory-cli/src/commands/render_shared.rs) order +
// vocabulary. The hook spools every event; SessionStart/SessionEnd also drain (and SessionStart injects
// the handoff + orientation), and UserPromptSubmit also injects proactive recall. PreToolUse/PostToolUse/
// PreCompact/Stop round out the observation stream. Other clients wire their own event names (see
// clients.go) but always invoke the SAME canonical `hook --event <kind>` command.
var ManagedHookEvents = []string{
	"SessionStart",
	"UserPromptSubmit",
	"PreToolUse",
	"PostToolUse",
	"PreCompact",
	"Stop",
	"SessionEnd",
}

// managedHookCommand is the shell command a client runs for a canonical event: the agent-memory binary
// (double quoted so a path with spaces — common on Windows — works) plus `hook --event <eventArg>`.
func managedHookCommand(binPath, eventArg string) string {
	return `"` + binPath + `" hook --event ` + eventArg
}

// isManagedHookCommand reports whether a hook command string is one of ours, identified by the trailing
// `hook --event <token>` (a single non-empty token, at a word boundary). Matching the tail — rather than
// a fixed event list or the binary path — keeps removal robust across upgrades that change the binary
// location AND across clients that map to different canonical events (e.g. Gemini's PreCompact), so any
// client's managed entry is recognized uniformly.
func isManagedHookCommand(cmd string) bool {
	const marker = "hook --event "
	i := strings.LastIndex(cmd, marker)
	if i < 0 {
		return false
	}
	if i > 0 && cmd[i-1] != ' ' {
		return false // part of a longer word, e.g. "rehook --event x" — not ours
	}
	arg := cmd[i+len(marker):]
	return arg != "" && !strings.ContainsAny(arg, " \t")
}

// Hooks idempotently wires the agent-memory capture hook into the Claude Code settings file at path
// (.claude/settings.json). It is the default-client convenience wrapper over HooksProfile and preserves
// the exact pre-#115 behavior.
func Hooks(path, binPath string) (Change, error) {
	return HooksProfile(path, &HookProfile{Shape: HookShapeNested, Events: claudeCodeHookEvents}, binPath)
}

// HooksProfile idempotently wires the agent-memory capture hook into a client's hooks file at path,
// rendering the client's shape (nested vs flat) and event names from h. It preserves every other key
// and any foreign hooks, removes any existing managed entries, adds fresh ones for binPath, and writes
// only if the canonical form actually changed:
//   - file/entries absent before → Created;
//   - managed entries present and now different (e.g. an upgraded binary path) → Updated;
//   - already exactly as desired → Unchanged (the file is left untouched).
func HooksProfile(path string, h *HookProfile, binPath string) (Change, error) {
	root, _, err := loadJSONObject(path)
	if err != nil {
		return "", err
	}
	before, err := marshalJSONStable(root)
	if err != nil {
		return "", err
	}
	hadManaged := hasManagedHooks(root)

	removeManagedHooks(root)
	addManagedHooks(root, h, binPath)

	after, err := marshalJSONStable(root)
	if err != nil {
		return "", err
	}
	if bytes.Equal(before, after) {
		return Unchanged, nil
	}
	if err := atomicWrite(path, after, 0o644); err != nil {
		return "", err
	}
	if hadManaged {
		return Updated, nil
	}
	return Created, nil
}

// UninstallHooks removes the agent-memory managed hook entries from a Claude Code settings file (the
// default-client wrapper over UninstallHooksProfile).
func UninstallHooks(path string) (Change, error) {
	return UninstallHooksProfile(path, &HookProfile{Shape: HookShapeNested, Events: claudeCodeHookEvents})
}

// UninstallHooksProfile removes the agent-memory managed hook entries from path, leaving foreign hooks
// and keys intact. The removal scans by managed-command tail and so handles either shape; h is accepted
// for symmetry and future shape-specific cleanup. A missing file, or one with no managed entries, is
// Absent.
func UninstallHooksProfile(path string, _ *HookProfile) (Change, error) {
	root, existed, err := loadJSONObject(path)
	if err != nil {
		return "", err
	}
	if !existed || !hasManagedHooks(root) {
		return Absent, nil
	}
	removeManagedHooks(root)
	after, err := marshalJSONStable(root)
	if err != nil {
		return "", err
	}
	if err := atomicWrite(path, after, 0o644); err != nil {
		return "", err
	}
	return Removed, nil
}

// hasManagedHooks reports whether root currently carries any agent-memory hook entry, in either the
// nested or the flat shape (both store entries under the top-level "hooks" map; they differ only in the
// per-event entry structure, which entryCommands abstracts over).
func hasManagedHooks(root map[string]any) bool {
	hooks, ok := root["hooks"].(map[string]any)
	if !ok {
		return false
	}
	for _, entriesAny := range hooks {
		entries, ok := entriesAny.([]any)
		if !ok {
			continue
		}
		for _, e := range entries {
			for _, cmd := range entryCommands(e) {
				if isManagedHookCommand(cmd) {
					return true
				}
			}
		}
	}
	return false
}

// entryCommands returns every hook command string an entry carries, abstracting over the two shapes:
//   - nested: {matcher, hooks:[{type:"command", command}]} — returns each inner command;
//   - flat:   {command} — returns the single command.
//
// A foreign/unrecognized entry yields no commands (so it is never matched as managed).
func entryCommands(e any) []string {
	ent, ok := e.(map[string]any)
	if !ok {
		return nil
	}
	// Flat shape: a direct command on the entry.
	if cmd, ok := ent["command"].(string); ok {
		return []string{cmd}
	}
	// Nested shape: a group with an inner hooks array.
	inner, ok := ent["hooks"].([]any)
	if !ok {
		return nil
	}
	var cmds []string
	for _, h := range inner {
		if hm, ok := h.(map[string]any); ok {
			if cmd, ok := hm["command"].(string); ok {
				cmds = append(cmds, cmd)
			}
		}
	}
	return cmds
}

// removeManagedHooks strips every agent-memory hook entry from root (either shape), dropping any group,
// nested entry, and event that becomes empty as a result, and the top-level "hooks" map if it ends up
// empty. Foreign entries are preserved exactly. The flat-shape "version" marker is left untouched (it is
// the file's own, not ours).
func removeManagedHooks(root map[string]any) {
	hooks, ok := root["hooks"].(map[string]any)
	if !ok {
		return
	}
	for event, entriesAny := range hooks {
		entries, ok := entriesAny.([]any)
		if !ok {
			continue
		}
		kept := make([]any, 0, len(entries))
		for _, e := range entries {
			pruned, drop := pruneEntry(e)
			if drop {
				continue
			}
			kept = append(kept, pruned)
		}
		if len(kept) == 0 {
			delete(hooks, event)
		} else {
			hooks[event] = kept
		}
	}
	if len(hooks) == 0 {
		delete(root, "hooks")
	}
}

// pruneEntry decides an entry's fate during removal. A flat entry whose command is ours is dropped. A
// nested group has its inner managed commands stripped: if no inner hooks remain the whole group is
// dropped, otherwise the trimmed group is kept. Foreign entries are returned unchanged.
func pruneEntry(e any) (kept any, drop bool) {
	ent, ok := e.(map[string]any)
	if !ok {
		return e, false
	}
	// Flat entry.
	if cmd, ok := ent["command"].(string); ok {
		return e, isManagedHookCommand(cmd)
	}
	// Nested group.
	inner, ok := ent["hooks"].([]any)
	if !ok {
		return e, false
	}
	keptInner := make([]any, 0, len(inner))
	for _, h := range inner {
		if hm, ok := h.(map[string]any); ok {
			if cmd, _ := hm["command"].(string); isManagedHookCommand(cmd) {
				continue
			}
		}
		keptInner = append(keptInner, h)
	}
	if len(keptInner) == 0 {
		return nil, true // the whole group was ours
	}
	ent["hooks"] = keptInner
	return ent, false
}

// addManagedHooks appends fresh agent-memory hook entries for each of the profile's events, in the
// profile's shape, alongside any foreign entries already present.
func addManagedHooks(root map[string]any, h *HookProfile, binPath string) {
	hooks, ok := root["hooks"].(map[string]any)
	if !ok {
		hooks = map[string]any{}
		root["hooks"] = hooks
	}
	for _, ev := range h.Events {
		cmd := managedHookCommand(binPath, ev.eventArg)
		var entry map[string]any
		switch h.Shape {
		case HookShapeFlat:
			// Cursor's flat shape: a direct {type, command, matcher} entry with NO inner hooks array,
			// mirroring ai-memory's HookShape::Flat (render_shared.rs build_hook_payload_for_platform).
			entry = map[string]any{
				"type":    "command",
				"command": cmd,
				"matcher": "",
			}
		default: // HookShapeNested
			entry = map[string]any{
				"matcher": "",
				"hooks": []any{
					map[string]any{
						"type":    "command",
						"command": cmd,
					},
				},
			}
		}
		existing, _ := hooks[ev.clientName].([]any)
		hooks[ev.clientName] = append(existing, entry)
	}
	// The flat (Cursor) schema carries a top-level format version; set it only when absent so a user's
	// existing value is preserved and idempotency holds.
	if h.Shape == HookShapeFlat {
		if _, ok := root["version"]; !ok {
			root["version"] = 1
		}
	}
}
