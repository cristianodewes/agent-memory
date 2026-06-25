package drain

import "context"

// Session boundary orchestration (ARCHITECTURE §2.1/§3.1, §3.4). The native hook fires the drain at
// the two session boundaries with slightly different shapes:
//
//   - session-start: a SHORT backlog drain (ship whatever is left from a previous run) and THEN fetch
//     the handoff to inject into the new session.
//   - session-end: the main drain (ship this session's captured events).
//
// Handoff fetch/injection is M4 (#23) and out of scope for #10; this file provides the seam — a
// HandoffFetcher the boundary calls after the start-drain — so #23 can plug in without touching the
// drain logic. The default fetcher is a no-op.

// HandoffFetcher fetches and injects the pending handoff at session-start, after the backlog drain.
// Implemented by #23; until then the no-op NoHandoff is used. It is intentionally separate from the
// drain so the capture/sync path and the recall/injection path stay decoupled.
type HandoffFetcher interface {
	// FetchHandoff is called once at session-start after the backlog drain completes. An error is
	// advisory (logged by the caller); it must not fail the session start.
	FetchHandoff(ctx context.Context) error
}

// HandoffFetcherFunc adapts a function to HandoffFetcher.
type HandoffFetcherFunc func(ctx context.Context) error

// FetchHandoff calls the wrapped function.
func (f HandoffFetcherFunc) FetchHandoff(ctx context.Context) error { return f(ctx) }

// NoHandoff is the default no-op fetcher (the #23 seam, unimplemented in #10).
var NoHandoff HandoffFetcher = HandoffFetcherFunc(func(_ context.Context) error { return nil })

// OnSessionStart runs the session-start boundary: a backlog drain followed by the handoff fetch. The
// drain Result is returned; the handoff fetch error is returned separately and is advisory (a failed
// handoff fetch must not abort the session). Either may be acted on by the caller (the `hook`
// command), which logs but does not fail the agent.
func (d *Drainer) OnSessionStart(ctx context.Context, handoff HandoffFetcher) (Result, error, error) {
	res, drainErr := d.Drain(ctx)
	if handoff == nil {
		handoff = NoHandoff
	}
	handoffErr := handoff.FetchHandoff(ctx)
	return res, drainErr, handoffErr
}

// OnSessionEnd runs the session-end boundary: the main drain. Returns the drain Result and error.
func (d *Drainer) OnSessionEnd(ctx context.Context) (Result, error) {
	return d.Drain(ctx)
}
