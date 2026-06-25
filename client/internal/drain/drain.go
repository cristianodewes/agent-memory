// Package drain ships spooled events to the server at session boundaries (ARCHITECTURE §2.1/§3.1),
// honoring backpressure and quarantining bad entries. It is the sync half of the fire-and-forget
// capture model: the `hook` command never blocks the agent (it only appends to the spool, #10), and
// this drain — run at session-start (a short backlog drain) and session-end (the main drain) — does
// the network work off the hot path.
//
// # Robustness (issue #10 acceptance)
//
//   - A single corrupt spool entry is quarantined (moved aside), never blocking or failing the rest
//     of the drain — this designs out the documented prior-art "batch-drain stalls on one bad event"
//     bug.
//   - The server's 202/429 is honored: 202 clears the shipped entries; 429 triggers a bounded
//     backoff-and-retry, after which the drain stops and LEAVES the spool intact (nothing dropped).
//   - A transport failure (server down) likewise leaves the spool intact for the next boundary.
//
// Nothing is ever deleted before the server acknowledges it.
package drain

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/apiclient"
	"github.com/cristianodewes/agent-memory/client/internal/hook"
	"github.com/cristianodewes/agent-memory/client/internal/spool"
)

// Poster ships a batch of events and reports the server's disposition. *apiclient.Client satisfies
// it; tests inject a fake to drive 202/429/transport-error paths deterministically.
type Poster interface {
	PostHookBatch(ctx context.Context, events []hook.Payload) (apiclient.BatchResult, error)
}

// Options tunes a Drainer. Zero values fall back to sensible defaults via withDefaults.
type Options struct {
	// BatchSize caps how many events go in one /hook/batch POST. Default 100.
	BatchSize int
	// MaxRetries bounds 429 backoff attempts per batch before giving up (and leaving the spool
	// intact). Default 4.
	MaxRetries int
	// BaseBackoff is the first 429 backoff; it doubles each retry (capped by MaxBackoff). Default
	// 50ms (kept short so a session-start backlog drain stays snappy).
	BaseBackoff time.Duration
	// MaxBackoff caps the exponential backoff. Default 2s.
	MaxBackoff time.Duration
	// Sleep is the backoff sleeper; injectable so tests don't actually wait. Default time.Sleep-like
	// but context-aware.
	Sleep func(ctx context.Context, d time.Duration) error
}

func (o Options) withDefaults() Options {
	if o.BatchSize <= 0 {
		o.BatchSize = 100
	}
	if o.MaxRetries < 0 {
		o.MaxRetries = 0
	}
	if o.MaxRetries == 0 {
		o.MaxRetries = 4
	}
	if o.BaseBackoff <= 0 {
		o.BaseBackoff = 50 * time.Millisecond
	}
	if o.MaxBackoff <= 0 {
		o.MaxBackoff = 2 * time.Second
	}
	if o.Sleep == nil {
		o.Sleep = sleepContext
	}
	return o
}

// Drainer ships a spool to a server.
type Drainer struct {
	spool  *spool.Spool
	poster Poster
	opts   Options
}

// New builds a Drainer over the given spool and poster.
func New(s *spool.Spool, p Poster, opts Options) *Drainer {
	return &Drainer{spool: s, poster: p, opts: opts.withDefaults()}
}

// Result summarizes a drain pass.
type Result struct {
	// Sent is the number of events the server acknowledged (and that were cleared from the spool).
	Sent int
	// Quarantined is the number of corrupt entries moved aside this pass.
	Quarantined int
	// Remaining is the number of valid events still in the spool (e.g. left after a 429/transport
	// failure). Zero means a clean, fully-shipped drain.
	Remaining int
	// Throttled is true if the server returned 429 and the backoff budget was exhausted, so the
	// drain stopped early with events still spooled.
	Throttled bool
}

// ErrThrottled is returned (wrapped) when the drain stops because the server stayed saturated
// through the whole backoff budget. The spool is intact; a later boundary retries.
var ErrThrottled = errors.New("drain: server throttled (429) after retries; spool left intact")

// Drain ships all currently-spooled events in capture order, batching by Options.BatchSize.
//
// For each entry: an unreadable/corrupt file is quarantined and skipped (it never blocks the batch).
// A full batch of valid events is POSTed; on 202 the batch's spool files are removed, on 429 the
// drain backs off and retries that batch up to MaxRetries, and if still throttled it stops and
// returns ErrThrottled with the spool intact. A transport error stops the drain (spool intact) and
// is returned so the caller can retry at the next boundary.
func (d *Drainer) Drain(ctx context.Context) (Result, error) {
	var res Result
	entries, err := d.spool.List()
	if err != nil {
		return res, fmt.Errorf("drain: list spool: %w", err)
	}

	batch := make([]hook.Payload, 0, d.opts.BatchSize)
	names := make([]string, 0, d.opts.BatchSize)

	flush := func() (bool, error) {
		if len(batch) == 0 {
			return true, nil
		}
		ok, err := d.shipBatch(ctx, batch, names, &res)
		batch = batch[:0]
		names = names[:0]
		return ok, err
	}

	for _, e := range entries {
		if err := ctx.Err(); err != nil {
			res.Remaining = d.countRemaining()
			return res, err
		}
		p, readErr := d.spool.Read(e.Name)
		if readErr != nil {
			// Corrupt entry: quarantine and carry on — one bad event must not stall the drain.
			if qErr := d.spool.Quarantine(e.Name, readErr.Error()); qErr != nil {
				return res, fmt.Errorf("drain: quarantine %q: %w", e.Name, qErr)
			}
			res.Quarantined++
			continue
		}
		batch = append(batch, p)
		names = append(names, e.Name)

		if len(batch) >= d.opts.BatchSize {
			ok, err := flush()
			if err != nil || !ok {
				res.Remaining = d.countRemaining()
				return res, err
			}
		}
	}

	ok, err := flush()
	if err != nil || !ok {
		res.Remaining = d.countRemaining()
		return res, err
	}

	res.Remaining = d.countRemaining()
	return res, nil
}

// shipBatch POSTs one batch, applying 429 backoff. Returns ok=false (with a possibly-nil error) when
// the drain should stop early but leave the spool intact (throttled or rejected); returns a non-nil
// error for a transport failure. On acceptance it removes the shipped spool files and bumps res.Sent.
func (d *Drainer) shipBatch(
	ctx context.Context, batch []hook.Payload, names []string, res *Result) (bool, error) {
	backoff := d.opts.BaseBackoff
	for attempt := 0; ; attempt++ {
		result, err := d.poster.PostHookBatch(ctx, batch)
		if err != nil {
			// Transport failure (server down): leave everything spooled, retry next boundary.
			return false, fmt.Errorf("drain: ship batch: %w", err)
		}
		switch result.Outcome {
		case apiclient.BatchAccepted:
			for _, n := range names {
				if rmErr := d.spool.Remove(n); rmErr != nil {
					return false, fmt.Errorf("drain: clear acked entry %q: %w", n, rmErr)
				}
			}
			res.Sent += len(batch)
			return true, nil
		case apiclient.BatchThrottled:
			if attempt >= d.opts.MaxRetries {
				// Out of budget: stop, leave the spool intact (nothing dropped).
				res.Throttled = true
				return false, ErrThrottled
			}
			if err := d.opts.Sleep(ctx, backoff); err != nil {
				return false, err
			}
			backoff = nextBackoff(backoff, d.opts.MaxBackoff)
		case apiclient.BatchRejected:
			// A hard rejection of the whole request (not per-item — #8 returns 202 for per-item
			// invalid). Leave the spool intact rather than silently drop a batch of real events; a
			// later boundary (or an operator) can act. Surfaced as an error.
			return false, fmt.Errorf("drain: server rejected batch (HTTP %d); spool left intact",
				result.Status)
		}
	}
}

// countRemaining counts the valid events still spooled (excludes quarantine). Best-effort: a list
// error yields 0 so it never masks the real drain error.
func (d *Drainer) countRemaining() int {
	entries, err := d.spool.List()
	if err != nil {
		return 0
	}
	return len(entries)
}

// nextBackoff doubles d, capped at max.
func nextBackoff(d, max time.Duration) time.Duration {
	n := d * 2
	if n > max {
		return max
	}
	return n
}

// sleepContext sleeps for d but returns early if ctx is cancelled.
func sleepContext(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
