package cli

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/config"
)

// fourRecords is a fixed, deterministic log sample spanning every level, event, workspace and project
// the filter tests slice on. The timestamps are absolute so --since can be asserted without leaning on
// the wall clock.
const fourRecords = `{"time":"2026-06-27T10:00:00Z","level":"INFO","msg":"event captured","event":"PostToolUse","workspace":"ws1","project":"proj1"}
{"time":"2026-06-27T10:01:00Z","level":"WARN","msg":"handoff fetch failed","event":"SessionStart","workspace":"ws1","project":"proj2"}
{"time":"2026-06-27T10:02:00Z","level":"ERROR","msg":"drain failed","event":"SessionStart","workspace":"ws2","project":"proj1"}
{"time":"2026-06-27T10:03:00Z","level":"DEBUG","msg":"regular event","event":"PreToolUse","workspace":"ws1","project":"proj1"}
`

// --- flag wiring (shortcuts) ----------------------------------------------------------------------

func TestLogsShorthandsAreWired(t *testing.T) {
	cmd := newLogsCmd()
	cases := map[string]string{"f": "follow", "n": "tail", "g": "grep", "o": "format"}
	for short, long := range cases {
		fl := cmd.Flags().ShorthandLookup(short)
		if fl == nil {
			t.Fatalf("missing shorthand -%s", short)
		}
		if fl.Name != long {
			t.Fatalf("-%s maps to %q, want %q", short, fl.Name, long)
		}
	}
}

func TestLogsTailShorthandPrintsLastLines(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, "l1\nl2\nl3\nl4\nl5\n")

	stdout, _, err := runRoot(t, "", "logs", "-n", "2")
	if err != nil {
		t.Fatalf("logs -n: %v", err)
	}
	if got := strings.TrimSpace(stdout); got != "l4\nl5" {
		t.Fatalf("logs -n 2 = %q, want last two lines", got)
	}
}

// --- filters --------------------------------------------------------------------------------------

func TestLogsLevelFilter(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	stdout, _, err := runRoot(t, "", "logs", "--level", "warn")
	if err != nil {
		t.Fatalf("logs --level: %v", err)
	}
	// warn = minimum, so WARN and ERROR survive; INFO and DEBUG are dropped.
	if !strings.Contains(stdout, "handoff fetch failed") || !strings.Contains(stdout, "drain failed") {
		t.Fatalf("--level warn should keep WARN+ERROR:\n%s", stdout)
	}
	if strings.Contains(stdout, "event captured") || strings.Contains(stdout, "regular event") {
		t.Fatalf("--level warn should drop INFO/DEBUG:\n%s", stdout)
	}
}

func TestLogsEventFilter(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	stdout, _, err := runRoot(t, "", "logs", "--event", "PostToolUse")
	if err != nil {
		t.Fatalf("logs --event: %v", err)
	}
	if !strings.Contains(stdout, "event captured") {
		t.Fatalf("--event PostToolUse should keep the matching record:\n%s", stdout)
	}
	if strings.Contains(stdout, "SessionStart") || strings.Contains(stdout, "PreToolUse") {
		t.Fatalf("--event PostToolUse should drop other events:\n%s", stdout)
	}
}

func TestLogsGrepFilter(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	stdout, _, err := runRoot(t, "", "logs", "-g", "drain")
	if err != nil {
		t.Fatalf("logs -g: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(stdout), "\n")
	if len(lines) != 1 || !strings.Contains(lines[0], "drain failed") {
		t.Fatalf("-g drain should match exactly the one line:\n%s", stdout)
	}
}

func TestLogsWorkspaceAndProjectFilters(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	stdout, _, err := runRoot(t, "", "logs", "--workspace", "ws2")
	if err != nil {
		t.Fatalf("logs --workspace: %v", err)
	}
	if !strings.Contains(stdout, "drain failed") || strings.Contains(stdout, "event captured") {
		t.Fatalf("--workspace ws2 should keep only the ws2 record:\n%s", stdout)
	}

	stdout, _, err = runRoot(t, "", "logs", "--project", "proj2")
	if err != nil {
		t.Fatalf("logs --project: %v", err)
	}
	if !strings.Contains(stdout, "handoff fetch failed") || strings.Contains(stdout, "drain failed") {
		t.Fatalf("--project proj2 should keep only the proj2 record:\n%s", stdout)
	}
}

func TestLogsSinceAbsoluteTimestamp(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	stdout, _, err := runRoot(t, "", "logs", "--since", "2026-06-27T10:01:30Z")
	if err != nil {
		t.Fatalf("logs --since: %v", err)
	}
	// Only the 10:02 and 10:03 records are newer than the cutoff.
	if !strings.Contains(stdout, "drain failed") || !strings.Contains(stdout, "regular event") {
		t.Fatalf("--since should keep records after the cutoff:\n%s", stdout)
	}
	if strings.Contains(stdout, "event captured") || strings.Contains(stdout, "handoff fetch failed") {
		t.Fatalf("--since should drop records before the cutoff:\n%s", stdout)
	}
}

func TestLogsFiltersCombineWithAnd(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	// SessionStart AND level>=error → only the ERROR drain record (the WARN SessionStart is dropped).
	stdout, _, err := runRoot(t, "", "logs", "--event", "SessionStart", "--level", "error")
	if err != nil {
		t.Fatalf("logs combined: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(stdout), "\n")
	if len(lines) != 1 || !strings.Contains(lines[0], "drain failed") {
		t.Fatalf("combined filters should AND to one record:\n%s", stdout)
	}
}

func TestLogsTailAppliesAfterFilter(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	// Two SessionStart records exist; --tail 1 keeps the last of the FILTERED survivors (ERROR), not
	// the last raw line (DEBUG, which is filtered out).
	stdout, _, err := runRoot(t, "", "logs", "--event", "SessionStart", "--tail", "1")
	if err != nil {
		t.Fatalf("logs tail-after-filter: %v", err)
	}
	if got := strings.TrimSpace(stdout); !strings.Contains(got, "drain failed") || strings.Contains(got, "handoff") {
		t.Fatalf("--tail 1 should keep the last filtered survivor:\n%s", got)
	}
}

// --- format ---------------------------------------------------------------------------------------

func TestLogsFormatTextIsReadableAndUncolored(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	stdout, _, err := runRoot(t, "", "logs", "--format", "text", "--event", "PostToolUse")
	if err != nil {
		t.Fatalf("logs --format text: %v", err)
	}
	for _, want := range []string{"2026-06-27 10:00:00", " · INFO · ", "event captured", "event=PostToolUse", "project=proj1", "workspace=ws1"} {
		if !strings.Contains(stdout, want) {
			t.Fatalf("text format missing %q:\n%s", want, stdout)
		}
	}
	// Color only on a TTY; the test buffer is not one, so no ANSI escapes leak in.
	if strings.Contains(stdout, "\x1b[") {
		t.Fatalf("text format to a non-TTY must not be colored:\n%q", stdout)
	}
}

func TestLogsFormatJSONIsRawByteStream(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	stdout, _, err := runRoot(t, "", "logs", "--tail", "0")
	if err != nil {
		t.Fatalf("logs default: %v", err)
	}
	// Default (no flags) keeps the raw JSON byte-stream verbatim.
	if stdout != fourRecords {
		t.Fatalf("default json output should be the raw bytes:\ngot:\n%q\nwant:\n%q", stdout, fourRecords)
	}
}

// --- color rendering (unit) -----------------------------------------------------------------------

func TestColorizeLevel(t *testing.T) {
	if got := colorizeLevel("ERROR", false); got != "ERROR" {
		t.Fatalf("disabled color should pass through, got %q", got)
	}
	got := colorizeLevel("ERROR", true)
	if !strings.HasPrefix(got, "\x1b[31m") || !strings.HasSuffix(got, "\x1b[0m") {
		t.Fatalf("enabled color should wrap ERROR in red, got %q", got)
	}
	if colorizeLevel("MYSTERY", true) != "MYSTERY" {
		t.Fatalf("unknown level should not be colored")
	}
}

func TestToTextColored(t *testing.T) {
	r := &lineRenderer{w: &bytes.Buffer{}, text: true, color: true}
	out := r.toText(`{"time":"2026-06-27T10:00:00Z","level":"WARN","msg":"hi","event":"X"}`)
	if !strings.Contains(out, "\x1b[33mWARN\x1b[0m") {
		t.Fatalf("colored text should wrap WARN in yellow:\n%q", out)
	}
	if !strings.Contains(out, "event=X") {
		t.Fatalf("text should carry structured fields:\n%q", out)
	}
}

// --- --path ---------------------------------------------------------------------------------------

func TestLogsPathPrintsAndExits(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	stdout, _, err := runRoot(t, "", "logs", "--path")
	if err != nil {
		t.Fatalf("logs --path: %v", err)
	}
	want := filepath.Join(dataDir, "logs", "client.log")
	if got := strings.TrimSpace(stdout); got != want {
		t.Fatalf("--path = %q, want %q", got, want)
	}
	// --path must not dump log content.
	if strings.Contains(stdout, "event captured") {
		t.Fatalf("--path must print only the path:\n%s", stdout)
	}
}

func TestLogsPathRespectsDataDirFlag(t *testing.T) {
	envDir := t.TempDir()
	t.Setenv(config.EnvDataDir, envDir)
	flagDir := t.TempDir()

	stdout, _, err := runRoot(t, "", "logs", "--path", "--data-dir", flagDir)
	if err != nil {
		t.Fatalf("logs --path --data-dir: %v", err)
	}
	want := filepath.Join(config.ResolveDataDir(flagDir), "logs", "client.log")
	if got := strings.TrimSpace(stdout); got != want {
		t.Fatalf("--path --data-dir = %q, want %q", got, want)
	}
}

// --- validation -----------------------------------------------------------------------------------

func TestLogsRejectsBadFlags(t *testing.T) {
	dataDir := t.TempDir()
	t.Setenv(config.EnvDataDir, dataDir)
	seedLog(t, dataDir, fourRecords)

	cases := [][]string{
		{"logs", "--level", "loud"},
		{"logs", "--format", "yaml"},
		{"logs", "--since", "yesterday"},
		{"logs", "--grep", "("},
	}
	for _, args := range cases {
		if _, _, err := runRoot(t, "", args...); err == nil {
			t.Fatalf("expected error for %v", args)
		}
	}
}

// --- since parsing (unit) -------------------------------------------------------------------------

func TestParseSince(t *testing.T) {
	now := time.Now()
	if got, err := parseSince("15m"); err != nil || now.Sub(got) < 14*time.Minute || now.Sub(got) > 16*time.Minute {
		t.Fatalf("parseSince(15m) = %v, err %v", got, err)
	}
	if got, err := parseSince("1d"); err != nil || now.Sub(got) < 23*time.Hour || now.Sub(got) > 25*time.Hour {
		t.Fatalf("parseSince(1d) = %v, err %v", got, err)
	}
	if got, err := parseSince("1d12h"); err != nil || now.Sub(got) < 35*time.Hour || now.Sub(got) > 37*time.Hour {
		t.Fatalf("parseSince(1d12h) = %v, err %v", got, err)
	}
	want := time.Date(2026, 6, 27, 10, 0, 0, 0, time.UTC)
	if got, err := parseSince("2026-06-27T10:00:00Z"); err != nil || !got.Equal(want) {
		t.Fatalf("parseSince(RFC3339) = %v, err %v", got, err)
	}
	if _, err := parseSince("nonsense"); err == nil {
		t.Fatalf("parseSince should reject garbage")
	}
}

// --- rotation-resilient follow (deterministic) ----------------------------------------------------

// TestFollowerSurvivesRotation drives the poll loop by hand (no timers): it seeds the read position at
// end-of-file, appends a line, then forces a rotation by renaming the active file aside and recreating
// it smaller — exactly what the size rotator does — and asserts the follower keeps streaming the new
// file instead of going silent on the stale offset.
func TestFollowerSurvivesRotation(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "client.log")
	if err := os.WriteFile(path, []byte("line1\nline2\n"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}

	f := &follower{path: path}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	f.offset = info.Size()
	f.prev = info

	if got := f.poll(); len(got) != 0 {
		t.Fatalf("no new bytes yet, want nothing, got %v", got)
	}

	if err := os.WriteFile(path, []byte("line1\nline2\nline3\n"), 0o644); err != nil {
		t.Fatalf("append: %v", err)
	}
	if got := f.poll(); len(got) != 1 || got[0] != "line3" {
		t.Fatalf("after append want [line3], got %v", got)
	}

	// Force rotation: move the active file aside and recreate it smaller than the stale offset.
	if err := os.Rename(path, path+".1"); err != nil {
		t.Fatalf("rotate rename: %v", err)
	}
	if err := os.WriteFile(path, []byte("line4\n"), 0o644); err != nil {
		t.Fatalf("rotate recreate: %v", err)
	}
	if got := f.poll(); len(got) != 1 || got[0] != "line4" {
		t.Fatalf("after rotation want [line4] (stream must continue), got %v", got)
	}
}

// TestFollowerTruncatedInPlace covers the size-shrink signal when the same file is truncated and
// rewritten (no rename / identity change).
func TestFollowerTruncatedInPlace(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "client.log")
	if err := os.WriteFile(path, []byte("aaaa\nbbbb\ncccc\n"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}
	f := &follower{path: path}
	info, _ := os.Stat(path)
	f.offset = info.Size()
	f.prev = info

	if err := os.WriteFile(path, []byte("z\n"), 0o644); err != nil { // truncate smaller in place
		t.Fatalf("truncate: %v", err)
	}
	if got := f.poll(); len(got) != 1 || got[0] != "z" {
		t.Fatalf("after in-place truncation want [z], got %v", got)
	}
}

// syncBuf is a goroutine-safe buffer for the follow integration test (the follow loop writes from its
// own goroutine while the test reads).
type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuf) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

// TestFollowLogStreamsThenStops exercises the live follow loop and ctx cancellation end-to-end on a
// shrunk poll interval, including streaming across a forced rotation.
func TestFollowLogStreamsThenStops(t *testing.T) {
	restore := followPollInterval
	followPollInterval = 5 * time.Millisecond
	defer func() { followPollInterval = restore }()

	dir := t.TempDir()
	path := filepath.Join(dir, "client.log")
	if err := os.WriteFile(path, []byte(`{"level":"INFO","msg":"seed"}`+"\n"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}

	buf := &syncBuf{}
	r := &lineRenderer{w: buf}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		_ = followLog(ctx, r, path)
		close(done)
	}()

	// followLog seeds its start offset synchronously at entry; give the goroutine a beat to do so
	// before appending, so the new line is genuinely "after" the follow start (not skipped as seed).
	time.Sleep(50 * time.Millisecond)

	appendFileLine(t, path, `{"level":"WARN","msg":"after-seed"}`)
	waitForContains(t, buf, "after-seed")

	// Rotate under the follower, then keep writing: the stream must continue.
	if err := os.Rename(path, path+".1"); err != nil {
		t.Fatalf("rotate: %v", err)
	}
	if err := os.WriteFile(path, []byte(`{"level":"ERROR","msg":"post-rotation"}`+"\n"), 0o644); err != nil {
		t.Fatalf("recreate: %v", err)
	}
	waitForContains(t, buf, "post-rotation")

	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatalf("followLog did not return after context cancel")
	}
	// The seed line was before the follow start offset and must not be re-emitted.
	if strings.Contains(buf.String(), `"msg":"seed"`) {
		t.Fatalf("follow must not replay pre-existing content:\n%s", buf.String())
	}
}

func appendFileLine(t *testing.T, path, line string) {
	t.Helper()
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		t.Fatalf("open append: %v", err)
	}
	defer f.Close()
	if _, err := f.WriteString(line + "\n"); err != nil {
		t.Fatalf("write append: %v", err)
	}
}

func waitForContains(t *testing.T, buf *syncBuf, want string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(buf.String(), want) {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %q in:\n%s", want, buf.String())
}
