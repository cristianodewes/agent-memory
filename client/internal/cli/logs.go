package cli

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/config"
	applog "github.com/cristianodewes/agent-memory/client/internal/log"
	"github.com/cristianodewes/agent-memory/client/internal/spool"
	"github.com/spf13/cobra"
	"golang.org/x/term"
)

// defaultLogsTail is how many trailing lines `logs` prints when --tail is not given. Bounded so the
// command is useful without dumping the whole (already size-rotated) file.
const defaultLogsTail = 200

// followPollInterval is how often `logs --follow` checks the file for appended bytes. It is a var (not
// a const) only so tests can shrink it to keep the follow-through-rotation case fast and deterministic.
var followPollInterval = 500 * time.Millisecond

// newLogsCmd builds `agent-memory logs`: print (and optionally follow) the durable client log at
// <data-dir>/logs/client.log (#117). It exists so an operator can answer "what did the client do?"
// without hunting for the file or reaching for `cat`/`tail`. Beyond the raw tail it offers an inspector
// surface — filters (level/since/grep/event), a readable `text` format, and a rotation-resilient
// follow — while keeping the default (no flags ⇒ last 200 lines, raw JSON) unchanged (#124).
func newLogsCmd() *cobra.Command {
	var (
		tail      int
		follow    bool
		dataDir   string
		level     string
		since     string
		grep      string
		event     string
		workspace string
		project   string
		format    string
		noColor   bool
		showPath  bool
	)

	cmd := &cobra.Command{
		Use:   "logs",
		Short: "Print, filter and follow the client log (<data-dir>/logs/client.log)",
		Long: "Prints the durable, rotating client log written by the capture hooks. With --tail/-n it " +
			"shows only the last N lines; with --follow/-f it then streams appended lines (resilient to " +
			"rotation) until interrupted. Filters (--level, --since, --grep, --event/--workspace/--project) " +
			"combine with AND and apply to both the tail and the follow; --format text renders one readable " +
			"line per record, while json (default) keeps the raw byte-stream. --path prints the log path.",
		SilenceUsage: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			cfg := resolveDataDirCfg(dataDir)
			path := clientLogPath(cfg)
			out := cmd.OutOrStdout()

			// --path is a scriptable shortcut: print the absolute log path and exit, before touching it.
			if showPath {
				fmt.Fprintln(out, path)
				return nil
			}

			filter, err := buildLogFilter(level, since, grep, event, workspace, project)
			if err != nil {
				return err
			}
			textFmt, err := parseLogFormat(format)
			if err != nil {
				return err
			}
			r := &lineRenderer{
				w:      out,
				filter: filter,
				text:   textFmt,
				color:  textFmt && !noColor && os.Getenv("NO_COLOR") == "" && isTerminalWriter(out),
			}

			if err := tailFiltered(out, path, tail, r); err != nil {
				return err
			}
			if follow {
				return followLog(cmd.Context(), r, path)
			}
			return nil
		},
	}
	cmd.Flags().IntVarP(&tail, "tail", "n", defaultLogsTail, "print only the last N lines (0 = all)")
	cmd.Flags().BoolVarP(&follow, "follow", "f", false,
		"after printing, keep streaming appended lines (resilient to rotation; interrupt to stop)")
	cmd.Flags().StringVar(&dataDir, "data-dir", "",
		"data dir root (default: AGENT_MEMORY_DATA_DIR or ~/.agent-memory)")
	cmd.Flags().StringVar(&level, "level", "",
		"only records at or above this level (error|warn|info|debug)")
	cmd.Flags().StringVar(&since, "since", "",
		"only records newer than a duration (15m/2h/1d) or an RFC3339 timestamp")
	cmd.Flags().StringVarP(&grep, "grep", "g", "", "only lines matching this regular expression")
	cmd.Flags().StringVar(&event, "event", "", "only records for this event kind (structured field)")
	cmd.Flags().StringVar(&workspace, "workspace", "", "only records for this workspace (structured field)")
	cmd.Flags().StringVar(&project, "project", "", "only records for this project (structured field)")
	cmd.Flags().StringVarP(&format, "format", "o", "json", "output format: json (raw) or text (readable)")
	cmd.Flags().BoolVar(&noColor, "no-color", false, "disable colored level in --format text")
	cmd.Flags().BoolVar(&showPath, "path", false, "print the absolute log file path and exit")
	return cmd
}

// newDoctorCmd builds `agent-memory doctor`: a one-shot health snapshot — data dir, server URL,
// effective log level + log path, spool pending + quarantine counts, and the last few log lines —
// reusing internal/spool so an operator can triage without inspecting files by hand (#117).
func newDoctorCmd() *cobra.Command {
	var dataDir string
	var tail int

	cmd := &cobra.Command{
		Use:          "doctor",
		Short:        "Show client health: data dir, spool/quarantine counts, log path + last lines",
		SilenceUsage: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			cfg := resolveDataDirCfg(dataDir)
			out := cmd.OutOrStdout()
			level := applog.Resolve(verbosity(cmd), cfg.LogLevel, cfg.Debug)
			logPath := clientLogPath(cfg)
			pending, quarantined := spoolCounts(cfg)

			fmt.Fprintf(out, "data dir:          %s\n", cfg.DataDir)
			fmt.Fprintf(out, "server url:        %s\n", cfg.ServerURL)
			fmt.Fprintf(out, "log level:         %s\n", strings.ToLower(level.String()))
			fmt.Fprintf(out, "log file:          %s\n", logPath)
			fmt.Fprintf(out, "spool pending:     %d\n", pending)
			fmt.Fprintf(out, "spool quarantine:  %d\n", quarantined)
			fmt.Fprintf(out, "\n--- last %d log line(s) ---\n", tail)
			return printTail(out, logPath, tail)
		},
	}
	cmd.Flags().StringVar(&dataDir, "data-dir", "",
		"data dir root (default: AGENT_MEMORY_DATA_DIR or ~/.agent-memory)")
	cmd.Flags().IntVar(&tail, "tail", 10, "how many trailing log lines to show")
	return cmd
}

// resolveDataDirCfg loads the env config and applies an explicit --data-dir override (expanding ~ and
// absolutizing it the same way Load does), so `logs`/`doctor` can target a non-default data dir.
func resolveDataDirCfg(dataDir string) config.Config {
	cfg := config.Load()
	if strings.TrimSpace(dataDir) != "" {
		cfg.DataDir = config.ResolveDataDir(dataDir)
	}
	return cfg
}

// clientLogPath is the absolute path of the active client log under cfg's data dir.
func clientLogPath(cfg config.Config) string {
	return filepath.Join(cfg.LogsDir(), applog.FileName)
}

// spoolCounts reports the pending and quarantined entry counts, best-effort: any error (e.g. a
// never-created spool) reports zero rather than failing the snapshot.
func spoolCounts(cfg config.Config) (pending, quarantined int) {
	sp, err := spool.Open(cfg.SpoolDir())
	if err != nil {
		return 0, 0
	}
	if entries, err := sp.List(); err == nil {
		pending = len(entries)
	}
	if q, err := sp.QuarantinedCount(); err == nil {
		quarantined = q
	}
	return pending, quarantined
}

// printTail writes the last n lines of the file at path to w (n ≤ 0 prints all). A missing file is
// not an error — it prints a friendly "(no client log yet …)" note, since a fresh install simply has
// not logged anything yet. It is the raw, unfiltered tail used by `doctor`.
func printTail(w io.Writer, path string, n int) error {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		fmt.Fprintf(w, "(no client log yet at %s)\n", path)
		return nil
	}
	if err != nil {
		return fmt.Errorf("logs: read %q: %w", path, err)
	}
	lines := nonEmptyLines(data)
	if n > 0 && len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	for _, ln := range lines {
		fmt.Fprintln(w, ln)
	}
	return nil
}

// tailFiltered is the `logs` tail: it reads the file, keeps only the lines that pass r's filter, takes
// the last n of those survivors (n ≤ 0 = all), and renders each through r (raw JSON or text). Filtering
// before the n-cut means `--tail 200 --level error` shows the last 200 ERROR records, not whatever
// errors happen to fall in the last 200 raw lines. A missing file prints the same friendly note as
// printTail. With no filter and json format the output is byte-for-byte the legacy tail.
func tailFiltered(w io.Writer, path string, n int, r *lineRenderer) error {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		fmt.Fprintf(w, "(no client log yet at %s)\n", path)
		return nil
	}
	if err != nil {
		return fmt.Errorf("logs: read %q: %w", path, err)
	}
	matched := make([]string, 0)
	for _, ln := range nonEmptyLines(data) {
		if r.passes(ln) {
			matched = append(matched, ln)
		}
	}
	if n > 0 && len(matched) > n {
		matched = matched[len(matched)-n:]
	}
	for _, ln := range matched {
		r.write(ln)
	}
	return nil
}

// nonEmptyLines splits file bytes into lines, dropping a trailing newline so an N-line tail counts
// real records (not a phantom empty final line). An empty file yields no lines.
func nonEmptyLines(data []byte) []string {
	s := strings.TrimRight(string(data), "\n")
	if s == "" {
		return nil
	}
	return strings.Split(s, "\n")
}

// followLog streams records appended to path after the initial tail, polling on a fixed interval until
// the context is cancelled, and surviving a log rotation: when the active file is recreated (or
// truncated) under it, the follower restarts from the new file's beginning instead of silently going
// quiet. Each new line passes through r, so the live stream honors the same filters and format as the
// tail. It is the `tail -f` half of `logs --follow`; the one-shot tail already printed existing content,
// so it starts from the current end of file.
func followLog(ctx context.Context, r *lineRenderer, path string) error {
	f := &follower{path: path}
	// Start at the current end so only NEW records stream (the tail already printed what is there).
	if info, err := os.Stat(path); err == nil {
		f.offset = info.Size()
		f.prev = info
	}
	ticker := time.NewTicker(followPollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			for _, ln := range f.poll() {
				r.emit(ln)
			}
		}
	}
}

// follower tracks the read position in the active log file across polls and the partial trailing line
// not yet terminated by a newline (carry), so a record split across two reads is emitted exactly once
// and only when complete.
type follower struct {
	path   string
	offset int64
	carry  []byte
	prev   os.FileInfo
}

// poll reads whatever has been appended since the last call and returns the newly completed lines,
// detecting rotation first. Rotation is recognized either by the file shrinking below our offset (the
// active file was truncated/recreated smaller — the reliable, cross-platform signal) or by its identity
// changing (os.SameFile; precise on Unix, a harmless no-op on Windows where size-shrink carries it).
// On rotation it rewinds to the new file's start and drops any stale carry. An absent file (e.g. the
// brief window mid-rename) is not an error: it returns nothing and retries next tick.
func (f *follower) poll() []string {
	info, err := os.Stat(f.path)
	if err != nil {
		return nil
	}
	rotated := (f.prev != nil && !os.SameFile(f.prev, info)) || info.Size() < f.offset
	f.prev = info
	if rotated {
		f.offset = 0
		f.carry = nil
	}
	if info.Size() == f.offset {
		return nil
	}
	file, err := os.Open(f.path)
	if err != nil {
		return nil
	}
	defer file.Close()
	if _, err := file.Seek(f.offset, io.SeekStart); err != nil {
		return nil
	}
	buf, _ := io.ReadAll(file)
	f.offset += int64(len(buf))

	data := buf
	if len(f.carry) > 0 {
		data = append(append([]byte(nil), f.carry...), buf...)
	}
	lines, carry := splitLines(data)
	f.carry = carry
	return lines
}

// splitLines splits data into complete (newline-terminated) lines, returning the unterminated trailing
// remainder as carry (copied so it does not alias a reused read buffer). Empty/whitespace-only lines are
// dropped, matching the tail's non-empty semantics; a trailing \r is trimmed for safety.
func splitLines(data []byte) (lines []string, carry []byte) {
	for {
		i := bytes.IndexByte(data, '\n')
		if i < 0 {
			break
		}
		line := strings.TrimRight(string(data[:i]), "\r")
		if strings.TrimSpace(line) != "" {
			lines = append(lines, line)
		}
		data = data[i+1:]
	}
	return lines, append([]byte(nil), data...)
}

// lineRenderer applies a filter and renders a single log line, either raw (json) or as one readable
// line (text). It is shared by the tail and the follow loop so both honor the same flags.
type lineRenderer struct {
	w      io.Writer
	filter logFilter
	text   bool
	color  bool
}

// passes reports whether line survives the filter (always true when no filter is configured).
func (r *lineRenderer) passes(line string) bool { return r.filter.match(line) }

// write renders an already-filtered line: the raw line for json, or a formatted single line for text.
func (r *lineRenderer) write(line string) {
	if r.text {
		fmt.Fprintln(r.w, r.toText(line))
		return
	}
	fmt.Fprintln(r.w, line)
}

// emit filters then renders, the per-line entry point used by the follow loop.
func (r *lineRenderer) emit(line string) {
	if r.passes(line) {
		r.write(line)
	}
}

// toText renders one JSON record as `ts · LEVEL · msg · k=v …`, with the level colorized when enabled.
// The remaining structured fields are sorted for stable, testable output. A line that is not valid JSON
// is passed through unchanged so nothing is ever swallowed.
func (r *lineRenderer) toText(line string) string {
	m := decodeFields(line)
	if m == nil {
		return line
	}
	ts := stringField(m, "time")
	if t, ok := parseLogTime(ts); ok {
		ts = t.Format("2006-01-02 15:04:05")
	}
	level := strings.ToUpper(stringField(m, "level"))
	msg := stringField(m, "msg")

	keys := make([]string, 0, len(m))
	for k := range m {
		switch k {
		case "time", "level", "msg":
			// rendered separately
		default:
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	fields := make([]string, 0, len(keys))
	for _, k := range keys {
		fields = append(fields, fmt.Sprintf("%s=%v", k, m[k]))
	}

	parts := []string{ts, colorizeLevel(level, r.color), msg}
	if len(fields) > 0 {
		parts = append(parts, strings.Join(fields, " "))
	}
	return strings.Join(parts, " · ")
}

// logFilter is the combinable (AND) record filter shared by the tail and the follow. The zero value
// matches everything (no flags ⇒ unchanged default).
type logFilter struct {
	minLevel  slog.Level
	hasLevel  bool
	since     time.Time
	hasSince  bool
	grep      *regexp.Regexp
	event     string
	workspace string
	project   string
}

// needsParse reports whether any active filter requires decoding the JSON record (grep alone works on
// the raw line, so it does not).
func (lf logFilter) needsParse() bool {
	return lf.hasLevel || lf.hasSince || lf.event != "" || lf.workspace != "" || lf.project != ""
}

// match reports whether line passes every active filter. grep runs first on the raw line (cheap,
// substring/regex over the whole record); the structured filters then decode the JSON once. A line that
// is not valid JSON cannot satisfy a structured filter, so it is excluded when one is active.
func (lf logFilter) match(line string) bool {
	if lf.grep != nil && !lf.grep.MatchString(line) {
		return false
	}
	if !lf.needsParse() {
		return true
	}
	m := decodeFields(line)
	if m == nil {
		return false
	}
	if lf.hasLevel {
		lvl, ok := applog.ParseLevel(stringField(m, "level"))
		if !ok || lvl < lf.minLevel {
			return false
		}
	}
	if lf.hasSince {
		t, ok := parseLogTime(stringField(m, "time"))
		if !ok || t.Before(lf.since) {
			return false
		}
	}
	if lf.event != "" && stringField(m, "event") != lf.event {
		return false
	}
	if lf.workspace != "" && stringField(m, "workspace") != lf.workspace {
		return false
	}
	if lf.project != "" && stringField(m, "project") != lf.project {
		return false
	}
	return true
}

// buildLogFilter validates the filter flags and assembles a logFilter, returning a friendly error for
// any malformed value (bad level, unparseable --since, invalid regex) so the command fails fast.
func buildLogFilter(level, since, grep, event, workspace, project string) (logFilter, error) {
	var lf logFilter
	if s := strings.TrimSpace(level); s != "" {
		lvl, ok := applog.ParseLevel(s)
		if !ok {
			return lf, fmt.Errorf("logs: invalid --level %q (want error|warn|info|debug)", level)
		}
		lf.minLevel = lvl
		lf.hasLevel = true
	}
	if s := strings.TrimSpace(since); s != "" {
		t, err := parseSince(s)
		if err != nil {
			return lf, err
		}
		lf.since = t
		lf.hasSince = true
	}
	if s := strings.TrimSpace(grep); s != "" {
		re, err := regexp.Compile(s)
		if err != nil {
			return lf, fmt.Errorf("logs: invalid --grep %q: %w", grep, err)
		}
		lf.grep = re
	}
	lf.event = strings.TrimSpace(event)
	lf.workspace = strings.TrimSpace(workspace)
	lf.project = strings.TrimSpace(project)
	return lf, nil
}

// parseLogFormat maps the --format value to whether the text renderer is used. json (default) keeps the
// raw byte-stream; text renders a readable line. Anything else is an error.
func parseLogFormat(s string) (text bool, err error) {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "", "json":
		return false, nil
	case "text":
		return true, nil
	default:
		return false, fmt.Errorf("logs: invalid --format %q (want json or text)", s)
	}
}

// dayDurationRe matches a `<n>d` (days) component, which time.ParseDuration does not understand.
var dayDurationRe = regexp.MustCompile(`(?i)(\d+)d`)

// parseSince resolves a --since value to an absolute cutoff: an RFC3339 timestamp is taken as-is,
// otherwise it is read as a duration before now. Durations accept the stdlib units plus `d` for days
// (`15m`, `2h`, `1d`, `1d12h`), expanded to hours before time.ParseDuration sees them.
func parseSince(s string) (time.Time, error) {
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t, nil
	}
	expanded := dayDurationRe.ReplaceAllStringFunc(s, func(m string) string {
		n, _ := strconv.Atoi(strings.TrimRight(strings.ToLower(m), "d"))
		return strconv.Itoa(n*24) + "h"
	})
	d, err := time.ParseDuration(expanded)
	if err != nil || d < 0 {
		return time.Time{}, fmt.Errorf(
			"logs: invalid --since %q (want a duration like 15m/2h/1d or an RFC3339 timestamp)", s)
	}
	return time.Now().Add(-d), nil
}

// parseLogTime parses a record's `time` field, tolerating both the nanosecond and second RFC3339 forms.
func parseLogTime(s string) (time.Time, bool) {
	if s == "" {
		return time.Time{}, false
	}
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return t, true
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t, true
	}
	return time.Time{}, false
}

// decodeFields parses one JSON log line into a flat field map, or nil if it is not valid JSON.
func decodeFields(line string) map[string]any {
	var m map[string]any
	if err := json.Unmarshal([]byte(line), &m); err != nil {
		return nil
	}
	return m
}

// stringField returns m[k] as a string ("" if absent or not a string).
func stringField(m map[string]any, k string) string {
	if v, ok := m[k]; ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}

// ansiByLevel maps an uppercased slog level to its ANSI SGR color code.
var ansiByLevel = map[string]string{
	"ERROR": "31", // red
	"WARN":  "33", // yellow
	"INFO":  "32", // green
	"DEBUG": "36", // cyan
}

// colorizeLevel wraps level in its ANSI color when enabled; otherwise (or for an unknown level) it
// returns the level unchanged.
func colorizeLevel(level string, enabled bool) string {
	if !enabled {
		return level
	}
	code, ok := ansiByLevel[level]
	if !ok {
		return level
	}
	return "\x1b[" + code + "m" + level + "\x1b[0m"
}

// isTerminalWriter reports whether w is a real terminal, so color is only auto-enabled when a human is
// watching. A buffered or piped writer (tests, `| grep`) is not a terminal, so it stays plain.
func isTerminalWriter(w io.Writer) bool {
	f, ok := w.(*os.File)
	if !ok {
		return false
	}
	return term.IsTerminal(int(f.Fd()))
}
