// Package log is the agent-memory client's structured, durable observability: a log/slog logger that
// writes redacted JSON records to a size-rotating file under `<data-dir>/logs/client.log` (issue
// #117). It exists because the client was previously fire-and-forget with NO durable trail — the only
// runtime clues were transient stderr lines (visible solely under `claude --debug`) and the spool —
// so "what did the client do across the last sessions?" was unanswerable without the server.
//
// # Hot path
//
// Writing is fire-and-forget: a record is formatted and handed to a NON-BLOCKING in-memory buffer
// drained by one background goroutine (see asyncWriter), so logging can never stall the agent's
// capture budget (ARCHITECTURE invariant #5) even on a stalled/full disk. The short-lived `hook`
// process makes its records durable by calling Close before exit, which flushes the buffer.
//
// # Secrets
//
// Every record passes a redaction boundary (see redact.go): `token`/`Authorization` and similar are
// never written, and secrets embedded in strings are masked. This mirrors the server's sanitization
// boundary (invariant #6 / DD-010) on the client side.
package log

import (
	"io"
	"log/slog"
	"path/filepath"
	"strings"
)

const (
	// FileName is the active log file under the logs dir. Exported so the `logs`/`doctor` commands
	// can locate it without re-deriving the convention.
	FileName = "client.log"
	// defaultMaxSize caps a single log file before rotation (5 MiB). Chosen small enough that a single
	// file stays cheap to tail/grep, large enough to hold many sessions.
	defaultMaxSize = 5 << 20
	// defaultMaxBackups bounds retention: at most this many rotated files are kept (so total on-disk
	// log is ≈ (backups+1) × maxSize). Keeps the durable trail bounded without an external sweeper.
	defaultMaxBackups = 3
)

// Options configures New. Only Dir is required; the rest fall back to package defaults.
type Options struct {
	// Dir is the directory the log file lives in (typically config.Config.LogsDir(),
	// `<data-dir>/logs`). It is created if missing.
	Dir string
	// Level is the minimum level recorded. Resolve derives it from the flag + env.
	Level slog.Level
	// FileName overrides the active file name (default "client.log").
	FileName string
	// MaxSizeBytes overrides the per-file rotation threshold (default 5 MiB).
	MaxSizeBytes int64
	// MaxBackups overrides how many rotated files are retained (default 3). A negative value keeps
	// none (the active file is truncated on rotation).
	MaxBackups int
	// bufferSize overrides the async buffer depth; 0 uses the default. Unexported: tests set it via
	// the package-internal constructor to force drops deterministically.
	bufferSize int
}

// Logger is the client's structured logger. It embeds *slog.Logger, so Info/Warn/Error/Debug (and
// With) are available directly, and adds Close (flush + stop) plus introspection (Path, Level,
// Dropped) for the `logs`/`doctor` commands.
type Logger struct {
	*slog.Logger
	aw    *asyncWriter
	path  string
	level slog.Level
}

// New opens (creating the dir if needed) the rotating log file and returns a Logger writing redacted
// JSON records to it off the hot path. The caller MUST Close it before the process exits so buffered
// records are flushed — the typical pattern is `defer logger.Close()` right after construction.
func New(opts Options) (*Logger, error) {
	name := opts.FileName
	if name == "" {
		name = FileName
	}
	maxSize := opts.MaxSizeBytes
	if maxSize <= 0 {
		maxSize = defaultMaxSize
	}
	maxBackups := opts.MaxBackups
	switch {
	case maxBackups < 0:
		maxBackups = 0
	case maxBackups == 0:
		maxBackups = defaultMaxBackups
	}

	sink, err := openFileSink(opts.Dir, name, maxSize, maxBackups)
	if err != nil {
		return nil, err
	}
	aw := newAsyncWriter(sink, opts.bufferSize)
	handler := slog.NewJSONHandler(aw, &slog.HandlerOptions{
		Level:       opts.Level,
		ReplaceAttr: ReplaceAttr,
	})
	return &Logger{
		Logger: slog.New(handler),
		aw:     aw,
		path:   filepath.Join(opts.Dir, name),
		level:  opts.Level,
	}, nil
}

// Nop returns a Logger that discards everything. It is used where a real file logger is unavailable
// or unwanted (tests, code paths that must never fail on logging) so callers never need a nil check.
func Nop() *Logger {
	return &Logger{
		Logger: slog.New(slog.NewTextHandler(io.Discard, &slog.HandlerOptions{Level: slog.LevelError})),
		level:  slog.LevelError,
	}
}

// Close flushes buffered records and releases the file. Safe to call on a Nop logger (no-op) and to
// call more than once. Returns the underlying file's close error, if any.
func (l *Logger) Close() error {
	if l == nil || l.aw == nil {
		return nil
	}
	return l.aw.Close()
}

// Slog returns the embedded *slog.Logger for callers that take the stdlib type directly (the drain's
// Options.Logger and apiclient.WithLogger), so the same redaction + rotation applies to their records.
func (l *Logger) Slog() *slog.Logger { return l.Logger }

// Path returns the absolute path of the active log file ("" for a Nop logger).
func (l *Logger) Path() string { return l.path }

// Level returns the minimum level this logger records.
func (l *Logger) Level() slog.Level { return l.level }

// Dropped reports records dropped because the async buffer was full (a stalled disk) — 0 for a Nop
// logger. Non-zero is a sign the disk could not keep up, not data the hot path should have blocked on.
func (l *Logger) Dropped() uint64 {
	if l == nil || l.aw == nil {
		return 0
	}
	return l.aw.Dropped()
}

// ParseLevel maps a textual level ("debug"/"info"/"warn"/"warning"/"error", case-insensitive) to a
// slog.Level. ok is false for an empty or unrecognized string, so callers can fall through to the
// next precedence source rather than silently forcing a level.
func ParseLevel(s string) (slog.Level, bool) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "debug":
		return slog.LevelDebug, true
	case "info":
		return slog.LevelInfo, true
	case "warn", "warning":
		return slog.LevelWarn, true
	case "error":
		return slog.LevelError, true
	default:
		return slog.LevelInfo, false
	}
}

// Resolve picks the effective level from, in precedence order: the `-v/--verbose` count (≥1 ⇒ debug,
// the most explicit signal), then AGENT_MEMORY_LOG_LEVEL (when it names a valid level), then
// AGENT_MEMORY_DEBUG=truthy (⇒ debug), else the sensible default of info. Info is the default so the
// per-event capture cycle (logged at info) leaves a durable trail without any opt-in, while the
// verbose payload detail stays gated behind debug.
func Resolve(verbose int, envLevel string, debugEnv bool) slog.Level {
	if verbose > 0 {
		return slog.LevelDebug
	}
	if lvl, ok := ParseLevel(envLevel); ok {
		return lvl
	}
	if debugEnv {
		return slog.LevelDebug
	}
	return slog.LevelInfo
}
