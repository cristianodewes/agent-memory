package cli

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/core"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
	"github.com/cristianodewes/agent-memory/client/internal/spool"
)

// drainServer is a scripted /hook/batch endpoint: the first `throttle` requests answer 429, the rest
// 202; it counts the events it took. onFirst, when set, runs once on the first request (to simulate
// events captured while a drain is in flight).
type drainServer struct {
	mu       sync.Mutex
	requests int
	took     int
	throttle int
	onFirst  func()
}

func (s *drainServer) handler(t *testing.T) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var events []json.RawMessage
		if err := json.Unmarshal(body, &events); err != nil {
			t.Errorf("batch body must be a JSON array: %v", err)
		}
		s.mu.Lock()
		s.requests++
		n := s.requests
		first := s.onFirst
		s.onFirst = nil
		s.mu.Unlock()
		if first != nil {
			first()
		}
		if n <= s.throttle {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		s.mu.Lock()
		s.took += len(events)
		s.mu.Unlock()
		w.WriteHeader(http.StatusAccepted)
	}
}

// drainEnv points the client at a temp data dir and the given server, shortens the saturated wait so
// the tests never really sleep, and returns the spool the command will drain.
func drainEnv(t *testing.T, serverURL string) *spool.Spool {
	t.Helper()
	t.Setenv(config.EnvDataDir, t.TempDir())
	t.Setenv(config.EnvServerURL, serverURL)
	prevWait, prevMax := drainSaturatedWait, drainSaturatedMaxWait
	drainSaturatedWait, drainSaturatedMaxWait = time.Millisecond, time.Millisecond
	t.Cleanup(func() { drainSaturatedWait, drainSaturatedMaxWait = prevWait, prevMax })
	sp, err := spool.Open(config.Load().SpoolDir())
	if err != nil {
		t.Fatal(err)
	}
	return sp
}

func spoolEvents(t *testing.T, sp *spool.Spool, n int) {
	t.Helper()
	ws, _ := core.NewWorkspaceID("acme")
	proj, _ := core.NewProjectID("agent-memory")
	session := core.NewSessionID()
	base := time.Date(2026, 10, 22, 12, 0, 0, 0, time.UTC)
	for i := 0; i < n; i++ {
		p := hook.NewPayload("PostToolUse", session, ws, proj, base.Add(time.Duration(i)*time.Millisecond))
		if _, err := sp.Append(p); err != nil {
			t.Fatal(err)
		}
	}
}

func pending(t *testing.T, sp *spool.Spool) int {
	t.Helper()
	entries, err := sp.List()
	if err != nil {
		t.Fatal(err)
	}
	return len(entries)
}

func runDrainCmd(t *testing.T) (string, error) {
	t.Helper()
	root := newRootCmd()
	root.SetArgs([]string{"drain"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	err := root.Execute()
	return out.String(), err
}

// TestDrainCommandShipsTheWholeSpool (#158): unlike the hooks' 15 s boundary drain, `drain` keeps
// going until the spool is empty.
func TestDrainCommandShipsTheWholeSpool(t *testing.T) {
	ds := &drainServer{}
	srv := httptest.NewServer(ds.handler(t))
	defer srv.Close()
	sp := drainEnv(t, srv.URL)
	spoolEvents(t, sp, 250)

	out, err := runDrainCmd(t)
	if err != nil {
		t.Fatalf("drain: %v\n%s", err, out)
	}
	if ds.took != 250 || pending(t, sp) != 0 {
		t.Fatalf("server took %d, %d left spooled; want 250 and 0\n%s", ds.took, pending(t, sp), out)
	}
	if !strings.Contains(out, "sent 250") {
		t.Errorf("output must report the total sent:\n%s", out)
	}
}

// TestDrainCommandWaitsOutASaturatedServer: a server that stays 429 past one pass's own backoff makes
// the command wait and start another pass, instead of giving up with the spool still full.
func TestDrainCommandWaitsOutASaturatedServer(t *testing.T) {
	ds := &drainServer{throttle: 7} // more than one pass's 1 + 4 retries
	srv := httptest.NewServer(ds.handler(t))
	defer srv.Close()
	sp := drainEnv(t, srv.URL)
	spoolEvents(t, sp, 30)

	out, err := runDrainCmd(t)
	if err != nil {
		t.Fatalf("drain: %v\n%s", err, out)
	}
	if ds.took != 30 || pending(t, sp) != 0 {
		t.Fatalf("server took %d, %d left spooled; want 30 and 0\n%s", ds.took, pending(t, sp), out)
	}
	if !strings.Contains(out, "saturated") {
		t.Errorf("output must say it waited on a saturated server:\n%s", out)
	}
}

// TestDrainCommandStopsOnTransportErrorKeepingTheSpool: server down ⇒ the command fails and nothing
// leaves the spool.
func TestDrainCommandStopsOnTransportErrorKeepingTheSpool(t *testing.T) {
	sp := drainEnv(t, "http://127.0.0.1:1")
	spoolEvents(t, sp, 5)

	out, err := runDrainCmd(t)
	if err == nil {
		t.Fatalf("expected an error against a dead server\n%s", out)
	}
	if pending(t, sp) != 5 {
		t.Fatalf("all 5 events must stay spooled, %d remain", pending(t, sp))
	}
}

// TestDrainCommandLeavesEventsCapturedMeanwhileToTheHooks: a live session keeps appending while the
// drain runs; a handful of fresh events must not keep the command looping forever.
func TestDrainCommandLeavesEventsCapturedMeanwhileToTheHooks(t *testing.T) {
	ds := &drainServer{}
	srv := httptest.NewServer(ds.handler(t))
	defer srv.Close()
	sp := drainEnv(t, srv.URL)
	spoolEvents(t, sp, 150)
	ds.onFirst = func() { spoolEvents(t, sp, 3) }

	out, err := runDrainCmd(t)
	if err != nil {
		t.Fatalf("drain: %v\n%s", err, out)
	}
	if ds.took != 150 || pending(t, sp) != 3 {
		t.Fatalf("server took %d, %d left spooled; want 150 and 3\n%s", ds.took, pending(t, sp), out)
	}
}
