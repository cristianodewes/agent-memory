package install

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// loadJSONObject reads path as a JSON object into a generic map (so foreign keys round-trip untouched).
// A missing or empty file yields an empty map with existed reporting whether the file was there. A
// present-but-non-object or invalid JSON is an error — an installer must never clobber a config file it
// cannot parse.
func loadJSONObject(path string) (obj map[string]any, existed bool, err error) {
	raw, existed, err := readFileOrEmpty(path)
	if err != nil {
		return nil, false, err
	}
	if !existed || len(bytes.TrimSpace(raw)) == 0 {
		return map[string]any{}, existed, nil
	}
	if err := json.Unmarshal(raw, &obj); err != nil {
		return nil, existed, fmt.Errorf("install: %s is not valid JSON: %w", path, err)
	}
	if obj == nil {
		obj = map[string]any{}
	}
	return obj, existed, nil
}

// marshalJSONStable renders v as pretty JSON (2-space indent; encoding/json sorts object keys) with a
// trailing newline — a stable canonical form, so an unchanged config re-marshals byte-identically and
// the installer can report "no change" without rewriting the file.
func marshalJSONStable(v any) ([]byte, error) {
	out, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("install: marshal JSON: %w", err)
	}
	return append(out, '\n'), nil
}
