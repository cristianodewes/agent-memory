package install

import "bytes"

// ManagedHookEvents are the Claude Code lifecycle events agent-memory wires its capture hook onto. The
// hook spools every event; SessionStart/SessionEnd also drain (and SessionStart injects the handoff +
// orientation), and UserPromptSubmit also injects proactive recall. PreToolUse/PostToolUse/Stop round
// out the observation stream.
var ManagedHookEvents = []string{
	"SessionStart",
	"UserPromptSubmit",
	"PreToolUse",
	"PostToolUse",
	"Stop",
	"SessionEnd",
}

// managedHookCommand is the shell command Claude Code runs for event E: the agent-memory binary (double
// quoted so a path with spaces — common on Windows — works) plus `hook --event E`.
func managedHookCommand(binPath, event string) string {
	return `"` + binPath + `" hook --event ` + event
}

// isManagedHookCommand reports whether a settings.json hook command is one of ours, identified by the
// exact `hook --event <E>` tail for a managed event. Matching the tail (not the binary path) keeps
// removal robust across an upgrade that changes the binary location.
func isManagedHookCommand(cmd string) bool {
	for _, e := range ManagedHookEvents {
		if cmd == "" {
			return false
		}
		if hasHookEventSuffix(cmd, e) {
			return true
		}
	}
	return false
}

func hasHookEventSuffix(cmd, event string) bool {
	suffix := "hook --event " + event
	return len(cmd) >= len(suffix) && cmd[len(cmd)-len(suffix):] == suffix
}

// Hooks idempotently wires the agent-memory capture hook into the Claude Code settings file at path
// (.claude/settings.json), preserving every other key and any foreign hooks. It removes any existing
// managed entries and adds fresh ones for binPath, then writes only if the canonical JSON actually
// changed:
//   - file/entries absent before → Created;
//   - managed entries present and now different (e.g. an upgraded binary path) → Updated;
//   - already exactly as desired → Unchanged (the file is left untouched).
func Hooks(path, binPath string) (Change, error) {
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
	addManagedHooks(root, binPath)

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

// UninstallHooks removes the agent-memory managed hook entries from path, leaving foreign hooks and
// keys intact. A missing file, or one with no managed entries, is Absent.
func UninstallHooks(path string) (Change, error) {
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

// hasManagedHooks reports whether root currently carries any agent-memory hook entry.
func hasManagedHooks(root map[string]any) bool {
	hooks, ok := root["hooks"].(map[string]any)
	if !ok {
		return false
	}
	for _, groupsAny := range hooks {
		groups, ok := groupsAny.([]any)
		if !ok {
			continue
		}
		for _, g := range groups {
			grp, ok := g.(map[string]any)
			if !ok {
				continue
			}
			entries, ok := grp["hooks"].([]any)
			if !ok {
				continue
			}
			for _, e := range entries {
				ent, ok := e.(map[string]any)
				if !ok {
					continue
				}
				if cmd, _ := ent["command"].(string); isManagedHookCommand(cmd) {
					return true
				}
			}
		}
	}
	return false
}

// removeManagedHooks strips every agent-memory hook entry from root, dropping any group and any event
// that becomes empty as a result, and the top-level "hooks" map if it ends up empty. Foreign entries
// are preserved exactly.
func removeManagedHooks(root map[string]any) {
	hooks, ok := root["hooks"].(map[string]any)
	if !ok {
		return
	}
	for event, groupsAny := range hooks {
		groups, ok := groupsAny.([]any)
		if !ok {
			continue
		}
		var keptGroups []any
		for _, g := range groups {
			grp, ok := g.(map[string]any)
			if !ok {
				keptGroups = append(keptGroups, g)
				continue
			}
			entries, ok := grp["hooks"].([]any)
			if !ok {
				keptGroups = append(keptGroups, g)
				continue
			}
			var keptEntries []any
			for _, e := range entries {
				if ent, ok := e.(map[string]any); ok {
					if cmd, _ := ent["command"].(string); isManagedHookCommand(cmd) {
						continue
					}
				}
				keptEntries = append(keptEntries, e)
			}
			if len(keptEntries) == 0 {
				continue // the whole group was ours
			}
			grp["hooks"] = keptEntries
			keptGroups = append(keptGroups, grp)
		}
		if len(keptGroups) == 0 {
			delete(hooks, event)
		} else {
			hooks[event] = keptGroups
		}
	}
	if len(hooks) == 0 {
		delete(root, "hooks")
	}
}

// addManagedHooks appends a fresh agent-memory hook group (matcher "" — all matches) for each managed
// event, alongside any foreign groups already present.
func addManagedHooks(root map[string]any, binPath string) {
	hooks, ok := root["hooks"].(map[string]any)
	if !ok {
		hooks = map[string]any{}
		root["hooks"] = hooks
	}
	for _, event := range ManagedHookEvents {
		group := map[string]any{
			"matcher": "",
			"hooks": []any{
				map[string]any{
					"type":    "command",
					"command": managedHookCommand(binPath, event),
				},
			},
		}
		existing, _ := hooks[event].([]any)
		hooks[event] = append(existing, group)
	}
}
