package log

import (
	"bytes"
	"sync"
	"testing"
	"time"
)

// blockingSink models a stalled/full disk: every Write blocks until release is closed.
type blockingSink struct {
	release chan struct{}
}

func (b *blockingSink) Write(p []byte) (int, error) {
	<-b.release
	return len(p), nil
}
func (b *blockingSink) Close() error { return nil }

// memSink records everything written; safe for the single owning goroutine + a Close-time reader.
type memSink struct {
	mu     sync.Mutex
	buf    bytes.Buffer
	closed bool
}

func (m *memSink) Write(p []byte) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.buf.Write(p)
}
func (m *memSink) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closed = true
	return nil
}
func (m *memSink) String() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.buf.String()
}

// TestAsyncWriterNeverBlocksHotPath is the hot-path-budget proof (issue #117 acceptance): even with a
// completely stalled sink, every Write returns immediately — logging can never apply backpressure to
// the agent's fire-and-forget capture path (ARCHITECTURE invariant #5). Excess records are dropped,
// not blocked on.
func TestAsyncWriterNeverBlocksHotPath(t *testing.T) {
	sink := &blockingSink{release: make(chan struct{})}
	aw := newAsyncWriter(sink, 8) // tiny buffer so it fills almost immediately

	const n = 5000
	start := time.Now()
	for i := 0; i < n; i++ {
		if _, err := aw.Write([]byte("x\n")); err != nil {
			t.Fatalf("Write returned error: %v", err)
		}
	}
	elapsed := time.Since(start)

	if elapsed > 2*time.Second {
		t.Fatalf("logging blocked the hot path: %d writes took %v against a stalled sink", n, elapsed)
	}
	if aw.Dropped() == 0 {
		t.Fatal("expected dropped records when the sink is stalled and the buffer is full")
	}

	// Unblock and close cleanly so the goroutine drains and exits (no leak).
	close(sink.release)
	if err := aw.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
}

// TestAsyncWriterCloseFlushes proves Close drains buffered records and closes the sink — the property
// the short-lived `hook` process relies on to make its log durable before it exits.
func TestAsyncWriterCloseFlushes(t *testing.T) {
	sink := &memSink{}
	aw := newAsyncWriter(sink, 64)

	for _, s := range []string{"a\n", "b\n", "c\n"} {
		if _, err := aw.Write([]byte(s)); err != nil {
			t.Fatalf("Write: %v", err)
		}
	}
	if err := aw.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	if got := sink.String(); got != "a\nb\nc\n" {
		t.Fatalf("Close did not flush all records; got %q", got)
	}
	if !sink.closed {
		t.Fatal("Close did not close the sink")
	}
	// A Write after Close must not panic (channel is closed); it is dropped.
	if _, err := aw.Write([]byte("late\n")); err != nil {
		t.Fatalf("post-Close Write errored: %v", err)
	}
	if err := aw.Close(); err != nil {
		t.Fatalf("second Close should be a no-op: %v", err)
	}
}
