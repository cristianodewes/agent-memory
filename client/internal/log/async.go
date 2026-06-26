package log

import (
	"io"
	"sync/atomic"
	"time"
)

// defaultBufferSize is how many formatted log records the async buffer holds before the hot path
// starts dropping. A capture process emits a handful of records, so this is never reached in normal
// use; the bound exists only so a stalled disk can never apply backpressure to the agent's hot path.
const defaultBufferSize = 1024

// closeFlushTimeout bounds how long Close waits for the background goroutine to flush buffered records
// before giving up. A healthy sink flushes in well under this; a WEDGED sink (full/stalled disk, hung
// network FS) would otherwise make Close — which runs on the hook's SYNCHRONOUS exit path
// (`defer logger.Close()`) — block the process on the way out, applying backpressure on the exit path
// and violating the fire-and-forget budget (ARCHITECTURE invariant #5). When it expires we abandon the
// flush; the goroutine still owns and will close the sink if it ever unblocks, and the process is
// exiting regardless.
const closeFlushTimeout = 250 * time.Millisecond

// asyncWriter decouples the (hot-path) act of logging from the (slow) act of writing to disk. It is
// the io.Writer slog's JSON handler writes each finished record to: Write copies the record and does
// a NON-BLOCKING send onto a buffered channel, then returns immediately — so logging can never block
// the agent's fire-and-forget capture path (ARCHITECTURE invariant #5), even if the underlying file
// is on a stalled/full disk. A single background goroutine owns the sink and drains the channel, so
// the sink (and its rotation) is never touched concurrently.
//
// Shutdown is likewise bounded: Close signals a separate `quit` channel and waits at most
// closeFlushTimeout for the goroutine to flush and close the sink, so a wedged disk can never block
// the exit path either. The record channel is deliberately NEVER closed — Close uses `quit` instead —
// so a late Write racing Close can never panic on a closed channel (no recover needed).
//
// When the buffer is full the record is dropped and counted (Dropped): observability is best-effort
// and must yield to the hot-path budget, never the other way around.
type asyncWriter struct {
	ch      chan []byte
	quit    chan struct{}
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
		quit: make(chan struct{}),
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
		// After Close, nothing will drain new records; drop rather than enqueue a record that would
		// never be flushed. (The channel is never closed, so even a racing send here cannot panic.)
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

// run is the SINGLE owner of the sink: it serializes every write (and thus every rotation) AND is the
// ONLY closer of the sink, so Close never races a write/rotation on it. It drains the record channel
// until Close signals quit, then flushes whatever is already buffered (non-blocking) and closes the
// sink. A per-record write error is swallowed — logging is advisory and must never surface on the
// capture path.
func (a *asyncWriter) run() {
	defer close(a.done)
	for {
		select {
		case b := <-a.ch:
			_, _ = a.sink.Write(b)
		case <-a.quit:
			a.drainBuffered()
			_ = a.sink.Close()
			return
		}
	}
}

// drainBuffered writes whatever records are already queued, without waiting for new ones. If the sink
// is wedged it blocks here on the first write — exactly the case Close bounds with closeFlushTimeout.
func (a *asyncWriter) drainBuffered() {
	for {
		select {
		case b := <-a.ch:
			_, _ = a.sink.Write(b)
		default:
			return
		}
	}
}

// Close stops accepting records and waits — UP TO closeFlushTimeout — for the background goroutine to
// flush the buffer and close the sink. The bound is deliberate: a wedged sink must never block the
// hook's exit path (invariant #5). Safe to call more than once. The sink's close error is
// intentionally not propagated (logging is advisory and the process is exiting); Close returns nil.
func (a *asyncWriter) Close() error {
	if a.closed.Swap(true) {
		return nil
	}
	close(a.quit)
	select {
	case <-a.done:
	case <-time.After(closeFlushTimeout):
		// Sink wedged: abandon the flush so the (synchronous) caller can exit.
	}
	return nil
}

// Dropped reports how many records were dropped because the buffer was full (a stalled sink) or
// arrived after Close. A non-zero value is a signal that the disk could not keep up, not data loss
// the hot path should have waited on.
func (a *asyncWriter) Dropped() uint64 {
	return a.dropped.Load()
}
