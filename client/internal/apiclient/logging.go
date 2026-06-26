package apiclient

import (
	"log/slog"
	"net/http"
	"time"
)

// WithLogger wires a structured logger into the client so every server call leaves a debug-level
// trace (method, path, status, latency) in the durable client log (#117). It is implemented at the
// transport layer so a SINGLE seam covers every endpoint (batch drain, recall, briefing, handoff,
// scent) without threading a logger through each method.
//
// Only the request method, URL PATH, status and latency are logged — never headers or bodies — so the
// bearer token on `Authorization` (and any request payload) can never reach the log. A nil logger is
// ignored (the client stays as-is). Pass this AFTER WithHTTPClient if you use both, so the logging
// transport wraps the client you supplied rather than being discarded by it.
func WithLogger(l *slog.Logger) Option {
	return func(c *Client) {
		if l == nil {
			return
		}
		base := c.http.Transport
		if base == nil {
			base = http.DefaultTransport
		}
		// Clone the http.Client so we wrap its transport without mutating a caller-supplied client.
		clone := *c.http
		clone.Transport = &loggingRoundTripper{base: base, log: l}
		c.http = &clone
	}
}

// loggingRoundTripper logs one debug record per HTTP round trip. It records only non-sensitive
// request metadata; the response body is left untouched (the typed methods parse it).
type loggingRoundTripper struct {
	base http.RoundTripper
	log  *slog.Logger
}

// RoundTrip times the underlying round trip and logs its disposition at debug. A transport error is
// logged (and returned unchanged) so a server-down/timeout is visible in the durable log, not just on
// stderr. The error string passes the logger's redaction boundary before it is written.
func (rt *loggingRoundTripper) RoundTrip(req *http.Request) (*http.Response, error) {
	start := time.Now()
	resp, err := rt.base.RoundTrip(req)
	latency := time.Since(start)
	if err != nil {
		rt.log.Debug("http request failed",
			"method", req.Method,
			"path", req.URL.Path,
			"latencyMs", latency.Milliseconds(),
			"err", err.Error(),
		)
		return resp, err
	}
	rt.log.Debug("http request",
		"method", req.Method,
		"path", req.URL.Path,
		"status", resp.StatusCode,
		"latencyMs", latency.Milliseconds(),
	)
	return resp, err
}
