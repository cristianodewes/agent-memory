// Package spool implements the local on-disk event spool that keeps the agent's hot path off the
// network (fire-and-forget, ARCHITECTURE §2.1/§3.1, invariant #5). The native `hook` command appends
// a captured event here and returns immediately; the drain (internal/drain) ships spooled events to
// the server at session boundaries.
//
// # Format: one file per event
//
// Each event is a single JSON-encoded hook.Payload written to its own file, named by a time-sortable
// key so List returns events in capture order. One-file-per-event is chosen over an append log on
// purpose: it makes an atomic append a single create+rename (no partial-record tearing), makes an
// ack a single unlink, and bounds the blast radius of a corrupt entry to exactly one file — which is
// what lets the drain quarantine one bad event without losing or blocking the rest (issue #10).
//
// # Atomicity
//
// Append writes to a temporary file in the same directory, fsyncs it, then renames it into place.
// rename(2) within a directory is atomic, so a reader (the drain) never observes a half-written
// event, and a crash mid-append leaves at most an orphan *.tmp the next drain ignores.
//
// # Windows extended-length paths
//
// The data dir may be an extended-length path (a `\\?\C:\...` prefix). A documented prior-art bug had
// the native spool silently die under such a dir. We never re-prefix or strip the path — Go's os
// package handles `\\?\` natively — and we only ever join the configured dir with a plain base file
// name (no `..`, no separators), so a verbatim prefix is preserved through create/list/remove. The
// cross-platform path test exercises a `\\?\`-style dir.
package spool

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
)

const (
	// eventExt is the suffix of a committed event file. List only returns files with this suffix, so
	// in-flight *.tmp writes and the quarantine subdir are never mistaken for spooled events.
	eventExt = ".json"
	// tmpExt marks a not-yet-committed event being written by Append.
	tmpExt = ".tmp"
	// quarantineDir holds entries the drain could not parse/send-as-valid; kept (never deleted) for
	// inspection so nothing is lost silently (issue #10 acceptance).
	quarantineDir = "quarantine"
)

// Spool is a handle to an on-disk event spool rooted at a directory. It is safe for the typical
// usage pattern — many short-lived `hook` processes each Append once, a single drain process List/
// Read/Remove — because every mutation is a single atomic filesystem operation; it does not add an
// in-process lock (cross-process coordination is the filesystem's job here).
type Spool struct {
	dir string
}

// Entry is one spooled event as returned by List: its file Name (a base name, join with the spool
// dir to open) and the ModTime used only for age-based ordering ties.
type Entry struct {
	Name    string
	ModTime time.Time
}

// Open returns a Spool rooted at dir, creating dir (and its quarantine subdir) if absent. dir may be
// relative, absolute, or a Windows extended-length (`\\?\`) path; it is used verbatim (only cleaned
// of redundant separators) so an extended-length prefix is preserved.
func Open(dir string) (*Spool, error) {
	if strings.TrimSpace(dir) == "" {
		return nil, errors.New("spool: dir must not be empty")
	}
	clean := filepath.Clean(dir)
	if err := os.MkdirAll(clean, 0o755); err != nil {
		return nil, fmt.Errorf("spool: create dir %q: %w", clean, err)
	}
	if err := os.MkdirAll(filepath.Join(clean, quarantineDir), 0o755); err != nil {
		return nil, fmt.Errorf("spool: create quarantine dir: %w", err)
	}
	return &Spool{dir: clean}, nil
}

// Dir returns the spool's root directory.
func (s *Spool) Dir() string { return s.dir }

// Append writes one event to the spool atomically and returns the committed file's base name. This
// is the hot-path operation: it does local disk IO only (temp write, fsync, rename) and never
// touches the network, so it returns within the capture budget even when the server is down.
//
// The file name is derived from the payload's ClientEventID when present (so a replayed capture of
// the same event overwrites rather than duplicates), else a freshly minted time-sortable id. Either
// way the name sorts chronologically, giving List a stable capture order.
func (s *Spool) Append(p hook.Payload) (string, error) {
	name := eventFileName(p)
	final := filepath.Join(s.dir, name)

	data, err := json.Marshal(p)
	if err != nil {
		return "", fmt.Errorf("spool: marshal event: %w", err)
	}

	tmp, err := os.CreateTemp(s.dir, name+".*"+tmpExt)
	if err != nil {
		return "", fmt.Errorf("spool: create temp: %w", err)
	}
	tmpName := tmp.Name()
	// Best-effort cleanup if we bail before the rename commits.
	committed := false
	defer func() {
		if !committed {
			_ = tmp.Close()
			_ = os.Remove(tmpName)
		}
	}()

	if _, err := tmp.Write(data); err != nil {
		return "", fmt.Errorf("spool: write temp: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		return "", fmt.Errorf("spool: fsync temp: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return "", fmt.Errorf("spool: close temp: %w", err)
	}
	if err := os.Rename(tmpName, final); err != nil {
		return "", fmt.Errorf("spool: commit rename: %w", err)
	}
	committed = true
	return name, nil
}

// List returns the committed event entries (excluding *.tmp and the quarantine subdir) sorted in
// capture order (by file name, which is time-sortable; ModTime breaks any tie). An empty spool
// returns an empty slice and no error.
func (s *Spool) List() ([]Entry, error) {
	dirents, err := os.ReadDir(s.dir)
	if err != nil {
		return nil, fmt.Errorf("spool: read dir: %w", err)
	}
	var entries []Entry
	for _, de := range dirents {
		if de.IsDir() {
			continue
		}
		name := de.Name()
		if !strings.HasSuffix(name, eventExt) || strings.HasSuffix(name, tmpExt) {
			continue
		}
		info, err := de.Info()
		if err != nil {
			// The file vanished between ReadDir and Info (a concurrent drain Remove); skip it.
			continue
		}
		entries = append(entries, Entry{Name: name, ModTime: info.ModTime()})
	}
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].Name != entries[j].Name {
			return entries[i].Name < entries[j].Name
		}
		return entries[i].ModTime.Before(entries[j].ModTime)
	})
	return entries, nil
}

// Read parses the spooled event with the given base name into a hook.Payload. A read or JSON error
// is returned so the caller (the drain) can quarantine that one entry without aborting the batch.
func (s *Spool) Read(name string) (hook.Payload, error) {
	var p hook.Payload
	data, err := os.ReadFile(filepath.Join(s.dir, name))
	if err != nil {
		return p, fmt.Errorf("spool: read %q: %w", name, err)
	}
	if err := json.Unmarshal(data, &p); err != nil {
		return p, fmt.Errorf("spool: parse %q: %w", name, err)
	}
	return p, nil
}

// Remove deletes a committed event after the server has acked it. A missing file is not an error
// (an idempotent re-drain may try to remove an already-removed entry).
func (s *Spool) Remove(name string) error {
	err := os.Remove(filepath.Join(s.dir, name))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("spool: remove %q: %w", name, err)
	}
	return nil
}

// Quarantine moves a bad entry out of the active spool into the quarantine subdir so it stops
// blocking the drain but is never lost (it can be inspected later). The reason is recorded in a
// sibling `<name>.reason` file. A missing source is not an error (already handled).
func (s *Spool) Quarantine(name, reason string) error {
	src := filepath.Join(s.dir, name)
	dst := filepath.Join(s.dir, quarantineDir, name)
	if err := os.Rename(src, dst); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return fmt.Errorf("spool: quarantine %q: %w", name, err)
	}
	// Best-effort breadcrumb; failure to write the reason must not fail the quarantine itself.
	_ = os.WriteFile(dst+".reason", []byte(reason+"\n"), 0o644)
	return nil
}

// QuarantinedCount reports how many entries currently sit in quarantine (for tests / diagnostics).
func (s *Spool) QuarantinedCount() (int, error) {
	dirents, err := os.ReadDir(filepath.Join(s.dir, quarantineDir))
	if err != nil {
		return 0, fmt.Errorf("spool: read quarantine: %w", err)
	}
	n := 0
	for _, de := range dirents {
		if !de.IsDir() && strings.HasSuffix(de.Name(), eventExt) {
			n++
		}
	}
	return n, nil
}

// eventFileName derives a committed file's base name from the payload. A present ClientEventID keys
// the file (idempotent re-capture overwrites the same name); otherwise a fresh time-sortable UUIDv7
// is minted. The name is sanitized to a safe single path segment regardless of the id's contents.
func eventFileName(p hook.Payload) string {
	id := ""
	if p.ClientEventID != nil {
		id = *p.ClientEventID
	}
	if strings.TrimSpace(id) == "" {
		id = core.NewUUIDv7().String()
	}
	return sanitizeSegment(id) + eventExt
}

// sanitizeSegment reduces an arbitrary id to a safe single filename segment: path separators, NUL
// and other troublesome bytes become '_', so a hostile or odd ClientEventID can never escape the
// spool dir or collide with the tmp/quarantine machinery.
func sanitizeSegment(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r == '/' || r == '\\' || r == 0 || r == ':' || r == '*' || r == '?' ||
			r == '"' || r == '<' || r == '>' || r == '|':
			b.WriteByte('_')
		case r < 0x20:
			b.WriteByte('_')
		default:
			b.WriteRune(r)
		}
	}
	out := b.String()
	if out == "" || out == "." || out == ".." {
		return "_"
	}
	return out
}
