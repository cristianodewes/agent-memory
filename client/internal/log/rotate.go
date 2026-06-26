package log

import (
	"fmt"
	"os"
	"path/filepath"
)

// fileSink is a size-rotating append-only file writer. It owns one open file (`<dir>/<name>`, e.g.
// `client.log`) and, once that file would exceed maxSize, rotates it aside (`client.log` →
// `client.log.1`, shifting older backups up) keeping at most maxBackups numbered backups before the
// oldest is dropped. This is a deliberately tiny, dependency-free rotator (the issue's guidance: a
// minimal self-written size rotator over a heavy dependency) — bounded on-disk retention with no
// third-party code.
//
// It is NOT safe for concurrent writers: the logger drives it from a SINGLE background goroutine (see
// asyncWriter), so every Write/rotate/Close is serialized without a mutex. The hot path never touches
// it directly — it only enqueues to the async buffer.
type fileSink struct {
	dir        string
	name       string
	maxSize    int64
	maxBackups int

	f    *os.File
	size int64
}

// openFileSink opens (creating the dir and the file if absent) the active log file in append mode and
// seeds the running size from the existing file, so a restart continues toward the same rotation
// threshold instead of resetting it.
func openFileSink(dir, name string, maxSize int64, maxBackups int) (*fileSink, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("log: create logs dir %q: %w", dir, err)
	}
	s := &fileSink{dir: dir, name: name, maxSize: maxSize, maxBackups: maxBackups}
	if err := s.open(); err != nil {
		return nil, err
	}
	return s, nil
}

// open opens the active log file in append mode and records its current size.
func (s *fileSink) open() error {
	path := filepath.Join(s.dir, s.name)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return fmt.Errorf("log: open %q: %w", path, err)
	}
	size := int64(0)
	if info, err := f.Stat(); err == nil {
		size = info.Size()
	}
	s.f = f
	s.size = size
	return nil
}

// Write appends p to the active file, rotating first when the file is non-empty and p would push it
// past maxSize. A rotation failure is non-fatal: it is reported but the write still goes to the
// current file, so logging degrades to "no rotation" rather than losing the record.
func (s *fileSink) Write(p []byte) (int, error) {
	if s.maxSize > 0 && s.size > 0 && s.size+int64(len(p)) > s.maxSize {
		if err := s.rotate(); err != nil {
			// Keep writing to the current (un-rotated) file rather than dropping the record.
			n, werr := s.f.Write(p)
			s.size += int64(n)
			if werr != nil {
				return n, werr
			}
			return n, err
		}
	}
	n, err := s.f.Write(p)
	s.size += int64(n)
	return n, err
}

// rotate closes the active file, shifts the numbered backups up (dropping the oldest beyond
// maxBackups), renames the active file to `<name>.1`, and opens a fresh active file.
func (s *fileSink) rotate() error {
	if err := s.f.Close(); err != nil {
		return fmt.Errorf("log: close before rotate: %w", err)
	}
	base := filepath.Join(s.dir, s.name)
	// Drop the oldest backup that would fall off the end, then shift the rest up by one.
	if s.maxBackups > 0 {
		_ = os.Remove(fmt.Sprintf("%s.%d", base, s.maxBackups))
		for i := s.maxBackups - 1; i >= 1; i-- {
			// A missing intermediate backup is fine (nothing to shift yet).
			_ = os.Rename(fmt.Sprintf("%s.%d", base, i), fmt.Sprintf("%s.%d", base, i+1))
		}
		if err := os.Rename(base, base+".1"); err != nil && !os.IsNotExist(err) {
			// Could not move the active file aside: reopen it and report — we must not lose the handle.
			if oerr := s.open(); oerr != nil {
				return fmt.Errorf("log: rotate rename then reopen: %w", oerr)
			}
			return fmt.Errorf("log: rotate rename: %w", err)
		}
	} else {
		// No backups retained: truncate by removing the active file.
		_ = os.Remove(base)
	}
	return s.open()
}

// Close closes the active file. The single owning goroutine has already stopped by the time Close is
// called (see asyncWriter.Close), so no write can race it.
func (s *fileSink) Close() error {
	if s.f == nil {
		return nil
	}
	err := s.f.Close()
	s.f = nil
	if err != nil {
		return fmt.Errorf("log: close sink: %w", err)
	}
	return nil
}
