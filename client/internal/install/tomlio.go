package install

import (
	"bytes"
	"fmt"

	"github.com/BurntSushi/toml"
)

// loadTOMLObject reads path as a TOML document into a generic map (so foreign tables/keys round-trip).
// A missing or empty file yields an empty map with existed reporting whether the file was there. Invalid
// TOML is an error — an installer must never clobber a config file it cannot parse.
func loadTOMLObject(path string) (obj map[string]any, existed bool, err error) {
	raw, existed, err := readFileOrEmpty(path)
	if err != nil {
		return nil, false, err
	}
	obj = map[string]any{}
	if !existed || len(bytes.TrimSpace(raw)) == 0 {
		return obj, existed, nil
	}
	if err := toml.Unmarshal(raw, &obj); err != nil {
		return nil, existed, fmt.Errorf("install: %s is not valid TOML: %w", path, err)
	}
	if obj == nil {
		obj = map[string]any{}
	}
	return obj, existed, nil
}

// marshalTOMLStable renders v as TOML with a trailing newline. The BurntSushi encoder sorts map keys
// and emits direct keys before sub-tables, so a given map always marshals byte-identically — a stable
// canonical form that lets the installer detect "no change" and skip rewriting (preserving comments and
// hand-formatting in the untouched case). An empty map marshals to no bytes.
func marshalTOMLStable(v any) ([]byte, error) {
	out, err := toml.Marshal(v)
	if err != nil {
		return nil, fmt.Errorf("install: marshal TOML: %w", err)
	}
	if len(out) > 0 && out[len(out)-1] != '\n' {
		out = append(out, '\n')
	}
	return out, nil
}
