package install

import (
	"os"
	"strings"
)

// Instructions writes the canonical self-routing block into the instructions file at path
// (CLAUDE.md / AGENTS.md), idempotently:
//   - no managed block yet → append it after the existing content (Created);
//   - a managed block already present → replace it in place (Updated), or report Unchanged when it is
//     already byte-identical;
//
// The block is fenced by the stable BEGIN/END markers, so this never disturbs the rest of the file. The
// file (and its parent directory) is created if missing.
func Instructions(path string) (Change, error) {
	raw, _, err := readFileOrEmpty(path)
	if err != nil {
		return "", err
	}
	content := string(raw)
	desired := SelfRoutingSnippet()

	start, end, found := findBlock(content)
	if found {
		if content[start:end] == desired {
			return Unchanged, nil
		}
		updated := content[:start] + desired + content[end:]
		if err := atomicWrite(path, []byte(updated), 0o644); err != nil {
			return "", err
		}
		return Updated, nil
	}

	var out string
	if trimmed := strings.TrimRight(content, "\n"); trimmed == "" {
		out = desired
	} else {
		// Separate the appended block from existing prose with a single blank line.
		out = trimmed + "\n\n" + desired
	}
	if err := atomicWrite(path, []byte(out), 0o644); err != nil {
		return "", err
	}
	return Created, nil
}

// UninstallInstructions removes the managed self-routing block from path. A missing file or a file
// without the block is Absent (a clean no-op). When the block was the file's only content, the file is
// removed entirely; otherwise the remaining content is rewritten with the block excised.
func UninstallInstructions(path string) (Change, error) {
	raw, existed, err := readFileOrEmpty(path)
	if err != nil {
		return "", err
	}
	if !existed {
		return Absent, nil
	}
	content := string(raw)
	start, end, found := findBlock(content)
	if !found {
		return Absent, nil
	}

	combined := strings.TrimRight(content[:start]+content[end:], "\n")
	if combined == "" {
		// The block was all the file held — remove the file rather than leave it empty.
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			return "", err
		}
		return Removed, nil
	}
	if err := atomicWrite(path, []byte(combined+"\n"), 0o644); err != nil {
		return "", err
	}
	return Removed, nil
}

// findBlock locates the managed self-routing block in content, returning the byte range [start, end)
// spanning from the BEGIN marker through the newline after the END marker (so the range can be cut or
// replaced cleanly). A BEGIN marker with no END marker is treated as running to end-of-file (defensive
// against a hand-mangled block). Returns found=false when there is no BEGIN marker.
func findBlock(content string) (start, end int, found bool) {
	start = strings.Index(content, SelfRoutingBegin)
	if start == -1 {
		return 0, 0, false
	}
	rel := strings.Index(content[start:], SelfRoutingEnd)
	if rel == -1 {
		return start, len(content), true
	}
	end = start + rel + len(SelfRoutingEnd)
	if nl := strings.IndexByte(content[end:], '\n'); nl != -1 {
		end += nl + 1
	} else {
		end = len(content)
	}
	return start, end, true
}
