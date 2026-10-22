// Package apiclient is the typed HTTP client of the agent-memory server used by the client's hook
// drain (and, later, every CLI subcommand). It never touches Postgres or the wiki directly — the
// server is the single source of truth (DD-001). This file implements only what the spool drain
// (#10) needs: posting a batch of captured events to /hook/batch and reading back the server's
// accept/throttle disposition. The full read/write API surface lands with #17.
package apiclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/hook"
)

// Client is a thin HTTP client bound to one server base URL. It is safe for concurrent use (it wraps
// an *http.Client). A zero Timeout uses a sensible default.
type Client struct {
	baseURL string
	token   string
	http    *http.Client
}

// Option configures a Client.
type Option func(*Client)

// WithToken sets the bearer token sent on protected routes (DD-007). Empty token ⇒ no header.
func WithToken(token string) Option {
	return func(c *Client) { c.token = token }
}

// WithHTTPClient overrides the underlying *http.Client (e.g. to set a transport or timeout in tests).
func WithHTTPClient(h *http.Client) Option {
	return func(c *Client) { c.http = h }
}

// New builds a Client for baseURL (e.g. "http://127.0.0.1:8080"). A trailing slash is trimmed so
// path joins are clean.
func New(baseURL string, opts ...Option) *Client {
	c := &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http:    &http.Client{Timeout: 10 * time.Second},
	}
	for _, o := range opts {
		o(c)
	}
	return c
}

// BatchOutcome is the server's disposition of a /hook/batch POST, distilled to what the drain needs
// to decide whether to clear the spool, retry with backoff, or quarantine.
type BatchOutcome int

const (
	// BatchAccepted: the server accepted the batch (HTTP 202). The drain may delete the shipped
	// spool entries.
	BatchAccepted BatchOutcome = iota
	// BatchThrottled: the server is saturated (HTTP 429). The drain must back off and retry later;
	// the spool is left intact (nothing is dropped).
	BatchThrottled
	// BatchRejected: the server rejected the request outright (4xx other than 429). The batch as
	// sent will never be accepted as-is; the caller decides how to handle it (the drain treats a
	// per-item-invalid response as still-acked, since the items were processed — see #8 semantics).
	BatchRejected
	// BatchPartial: the server answered 2xx but its per-item report marks some items "throttled"
	// (its ingest queue filled mid-batch, #158). Only the other items are done; the drain must keep
	// the indexes in BatchResult.Throttled spooled and retry them.
	BatchPartial
)

// BatchResult is the parsed outcome of a /hook/batch POST.
type BatchResult struct {
	Outcome BatchOutcome
	// Status is the raw HTTP status code, for logging.
	Status int
	// Throttled lists the indexes (into the posted batch) the server did not take because it was
	// saturated. Set only for BatchPartial.
	Throttled []int
}

// batchReport is the per-item part of the server's /hook/batch response body (#8).
type batchReport struct {
	Results []struct {
		Index  int    `json:"index"`
		Status string `json:"status"`
	} `json:"results"`
}

// PostHookBatch ships a batch of captured events to POST /hook/batch and reports the server's
// disposition. A transport error (server down, DNS, timeout) is returned as an error so the drain
// keeps the spool and retries at the next boundary — the events are never lost. An empty batch is a
// no-op success.
func (c *Client) PostHookBatch(ctx context.Context, events []hook.Payload) (BatchResult, error) {
	if len(events) == 0 {
		return BatchResult{Outcome: BatchAccepted, Status: http.StatusAccepted}, nil
	}
	body, err := json.Marshal(events)
	if err != nil {
		return BatchResult{}, fmt.Errorf("apiclient: marshal batch: %w", err)
	}
	req, err := http.NewRequestWithContext(
		ctx, http.MethodPost, c.baseURL+"/hook/batch", bytes.NewReader(body))
	if err != nil {
		return BatchResult{}, fmt.Errorf("apiclient: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return BatchResult{}, fmt.Errorf("apiclient: POST /hook/batch: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	result := BatchResult{Outcome: outcomeFor(resp.StatusCode), Status: resp.StatusCode}
	if result.Outcome == BatchAccepted {
		result.Throttled = throttledIndexes(resp.Body, len(events))
		if len(result.Throttled) > 0 {
			result.Outcome = BatchPartial
		}
	}
	return result, nil
}

// throttledIndexes reads the per-item report of a 2xx /hook/batch response and returns the indexes
// marked "throttled" (#158): the server answers 202 as soon as one item was accepted, so this report
// is the only signal that the rest of the batch was turned away. A missing or unreadable report keeps
// the pre-#158 meaning of a 2xx — the whole batch acknowledged — and so does an out-of-range index.
func throttledIndexes(body io.Reader, n int) []int {
	var report batchReport
	if err := json.NewDecoder(io.LimitReader(body, 1<<20)).Decode(&report); err != nil {
		return nil
	}
	var throttled []int
	for _, r := range report.Results {
		if r.Status == "throttled" && r.Index >= 0 && r.Index < n {
			throttled = append(throttled, r.Index)
		}
	}
	return throttled
}

// outcomeFor maps an HTTP status to a BatchOutcome. 2xx ⇒ accepted (the batch was processed; #8
// returns 202 even when some items were per-item-invalid, which is still an ack of the drain). 429 ⇒
// throttled. Any other 4xx/5xx ⇒ rejected.
func outcomeFor(status int) BatchOutcome {
	switch {
	case status >= 200 && status < 300:
		return BatchAccepted
	case status == http.StatusTooManyRequests:
		return BatchThrottled
	default:
		return BatchRejected
	}
}
