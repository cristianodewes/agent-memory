package log

import (
	"io"
	"sync/atomic"
)

// defaultBufferSize is how many formatted log records the async buffer holds before the hot path
// starts dropping. A capture process emits a handful of records, so this is never reached in normal
// use; the bound exists only so a stalled disk can never apply backpressure to the agent's hot path.
const defaultBufferSize = 1024

// asyncWriter decouples the (hot-path) act of logging from the (slow) act of writing to disk. It is
// the io.Writer slog's JSON handler writes each finished record to: Write copies the record and does
// a NON-BLOCKING send onto a buffered channel, then returns immediately — so logging can never block
// the agent's fire-and-forget capture path (ARCHITECTURE invariant #5), even if the underlying file
// is on a stalled/full disk. A single background goroutine owns the sink and drains the channel, so
// the sink (and its rotation) is never touched concurrently.
//
// When the buffer is full the record is dropped and counted (Dropped): observability is best-effort
// and must yield to the hot-path budget, never the other way around. Close drains and flushes what is
// buffered before returning, which is what makes the short-lived `hook` process durable on exit.
type asyncWriter struct {
	ch      chan []byte
	done    chan struct{}
	sink    io.WriteCloser
	dropped atomic.Uint64
	closed  atomic.Bool
}

// newAsyncWriter starts the background drain goroutine over sink. bufferSize ≤ 0 uses the default.
func newAsyncWriter(sink io.WriteCloser, bufferSize int) *asyncWriter {
	if bufferSize <= 0 {
		bufferSize = defaultBufferSize
	}
	a := &asyncWriter{
		ch:   make(chan []byte, bufferSize),
		done: make(chan struct{}),
		sink: sink,
	}
	go a.run()
	return a
}

// Write enqueues a copy of p for the background goroutine and returns immediately. It never blocks:
// if the buffer is full (a stalled sink) the record is dropped and counted. The returned n is always
// len(p) with a nil error so slog treats the write as successful and never retries on the hot path.
func (a *asyncWriter) Write(p []byte) (int, error) {
	if a.closed.Load() {
		// After Close the channel is closed; sending would panic. Drop silently — nothing is reading.
		a.dropped.Add(1)
		return len(p), nil
	}
	// slog reuses its formatting buffer across records, so p must be copied before it leaves Write.
	b := make([]byte, len(p))
	copy(b, p)
	select {
	case a.ch <- b:
	default:
		a.dropped.Add(1)
	}
	return len(p), nil
}

// run is the single owner of the sink: it serializes every write (and thus every rotation) so the
// sink needs no internal locking. A per-record write error is swallowed — logging is advisory and
// must never surface on the capture path.
func (a *asyncWriter) run() {
	defer close(a.done)
	for b := range a.ch {
		_, _ = a.sink.Write(b)
	}
}

// Close stops accepting records, lets the background goroutine drain what is buffered, then closes
// the sink. It is safe to call once; subsequent Writes are dropped. Returns the sink's close error.
func (a *asyncWriter) Close() error {
	if a.closed.Swap(true) {
		return nil
	}
	close(a.ch)
	<-a.done
	return a.sink.Close()
}

// Dropped reports how many records were dropped because the buffer was full (a stalled sink) or
// arrived after Close. A non-zero value is a signal that the disk could not keep up, not data loss
// the hot path should have waited on.
func (a *asyncWriter) Dropped() uint64 {
	return a.dropped.Load()
}
