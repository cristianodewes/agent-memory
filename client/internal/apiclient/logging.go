package apiclient

import (
	"bytes"
	"io"
	"log/slog"
	"net/http"
	"time"
)

// maxLoggedBodyBytes bounds how much of a response body the opt-in body logger writes per record, so a
// large payload (a long briefing, a big recall block) cannot bloat the durable log. It bounds only the
// LOGGED slice — the full body is always restored for the typed parser (see teeResponseBody) — and a
// truncation note is logged when the body exceeds it. 64 KiB comfortably holds a typical recall/
// briefing/handoff JSON in full.
const maxLoggedBodyBytes = 64 << 10

// WithLogger wires a structured logger into the client so every server call leaves a debug-level
// trace (method, path, status, latency) in the durable client log (#117). It is implemented at the
// transport layer so a SINGLE seam covers every endpoint (batch drain, recall, briefing, handoff,
// scent) without threading a logger through each method.
//
// Only the request method, URL PATH, status and latency are logged — never headers, and never the
// response body unless the opt-in WithResponseBodyLogging is also applied (#126) — so the bearer token
// on `Authorization` (and any request payload) can never reach the log by default. A nil logger is
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

// WithResponseBodyLogging turns on the OPT-IN logging of the FULL response body (issue #126). It
// augments the logging transport WithLogger installs, so apply it AFTER WithLogger; with no logger
// (WithLogger absent or passed nil) it is a no-op, since there is nowhere to write the body.
//
// DATA-LEAK WARNING: when enabled, every server response body — i.e. MEMORY CONTENT (recall/inject,
// briefing, handoff, scent) — is written to the durable client log at debug. The bearer token /
// `Authorization` stays redacted (the logger's secret boundary still runs over the body string), but
// everything else the server returns is exposed in plaintext. This is a deliberate debugging aid;
// keep it OFF by default (config maps AGENT_MEMORY_LOG_RESPONSE_BODIES to it).
func WithResponseBodyLogging(enabled bool) Option {
	return func(c *Client) {
		if !enabled {
			return
		}
		if lrt, ok := c.http.Transport.(*loggingRoundTripper); ok {
			lrt.logBodies = true
		}
	}
}

// loggingRoundTripper logs one debug record per HTTP round trip. It records only non-sensitive
// request metadata; the response body is left untouched (the typed methods parse it) UNLESS logBodies
// is set (the opt-in #126 mode), in which case the body is tee'd into the record and restored intact.
type loggingRoundTripper struct {
	base http.RoundTripper
	log  *slog.Logger
	// logBodies enables the opt-in response-body logging (#126). OFF by default; a data-leak risk that
	// only the explicit AGENT_MEMORY_LOG_RESPONSE_BODIES toggle should turn on.
	logBodies bool
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
	attrs := []any{
		"method", req.Method,
		"path", req.URL.Path,
		"status", resp.StatusCode,
		"latencyMs", latency.Milliseconds(),
	}
	if rt.logBodies {
		// Opt-in (#126): tee the body so it lands in the record while the typed parser still sees the
		// full response. It is logged as a plain string attribute, so the logger's redaction boundary
		// masks any known secret (token/Authorization) before it is written — even in this mode.
		body, truncated := teeResponseBody(resp)
		attrs = append(attrs, "body", body)
		if truncated {
			attrs = append(attrs, "bodyTruncated", true, "bodyLimitBytes", maxLoggedBodyBytes)
		}
	}
	rt.log.Debug("http request", attrs...)
	return resp, err
}

// teeResponseBody drains resp.Body for logging and RESTORES it so the caller's typed parse still sees
// the FULL response — the "tee" of issue #126. The complete body is read back into resp.Body (so a
// parse is byte-for-byte unaffected at any size); only the returned preview is bounded by
// maxLoggedBodyBytes, with truncated=true when the body exceeded it. A read error leaves whatever was
// read restored (best effort): this is a debug-only path and the typed method surfaces any real error.
func teeResponseBody(resp *http.Response) (preview string, truncated bool) {
	if resp == nil || resp.Body == nil {
		return "", false
	}
	buf, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	resp.Body = io.NopCloser(bytes.NewReader(buf))
	if len(buf) > maxLoggedBodyBytes {
		return string(buf[:maxLoggedBodyBytes]), true
	}
	return string(buf), false
}
