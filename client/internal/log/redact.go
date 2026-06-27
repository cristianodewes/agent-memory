package log

import (
	"log/slog"
	"regexp"
	"strings"
)

// Redacted is the placeholder substituted for any value that is, or contains, a secret.
const Redacted = "[REDACTED]"

// sensitiveKeys are attribute keys whose VALUE is always a secret and is replaced wholesale,
// regardless of level. This is the client mirror of the server's sanitization boundary (ARCHITECTURE
// invariant #6 / DD-010): a credential must never reach the durable log. `token` and `Authorization`
// are the non-negotiable minimum from the issue; the rest are defense-in-depth for anything a caller
// might accidentally attach.
var sensitiveKeys = map[string]struct{}{
	"authorization":       {},
	"token":               {},
	"bearer":              {},
	"password":            {},
	"passwd":              {},
	"secret":              {},
	"apikey":              {},
	"api_key":             {},
	"api-key":             {},
	"access_token":        {},
	"refresh_token":       {},
	"client_secret":       {},
	"cookie":              {},
	"set-cookie":          {},
	"x-api-key":           {},
	"proxy-authorization": {},
}

// secretPatterns mask a secret embedded INSIDE an otherwise-loggable string (an error message, a URL
// with a token query param, a stray header dump). They are intentionally conservative — anchored on a
// keyword followed by an assignment or the literal `Bearer ` scheme — so ordinary text is left
// untouched while `Authorization: Bearer abc.def`, `token=sk-123` and `?access_token=xyz` are masked.
var secretPatterns = []*regexp.Regexp{
	// `Bearer <token>` (the exact shape apiclient sets on the Authorization header).
	regexp.MustCompile(`(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+`),
	// `key: value` / `key=value` / `"key":"value"` for a sensitive key, in JSON, query strings or prose.
	regexp.MustCompile(`(?i)\b(authorization|token|bearer|password|passwd|secret|api[_-]?key|access_token|refresh_token|client_secret)\b("?\s*[:=]\s*"?)([^\s",}&]+)`),
}

// maskSecrets returns s with any embedded secret replaced by Redacted, and whether it changed
// anything. A cheap keyword pre-check keeps the regex work off strings that obviously hold no secret
// (the common case), so this stays inexpensive even though it runs per string attribute.
//
// RESIDUAL RISK (accepted): masking is KEYWORD-anchored (`token=`, `Bearer …`, `password:` …). A
// "bare" credential carried with no surrounding keyword — e.g. a raw API key sitting alone in a body
// preview — would NOT be matched and could pass through. This is bounded by design: such a value is
// only ever logged at DEBUG (never the default), and it mirrors the server's sanitization, which is
// likewise pattern-based. The non-negotiable guarantee — `token`/`Authorization`-keyed ATTRS are
// dropped wholesale (see replaceAttr / sensitiveKeys) — does not depend on this heuristic.
func maskSecrets(s string) (string, bool) {
	if !mightContainSecret(s) {
		return s, false
	}
	out := s
	out = secretPatterns[0].ReplaceAllString(out, "Bearer "+Redacted)
	out = secretPatterns[1].ReplaceAllString(out, "$1$2"+Redacted)
	return out, out != s
}

// mightContainSecret is a fast lowercase-substring gate before the regexes run.
func mightContainSecret(s string) bool {
	l := strings.ToLower(s)
	for _, kw := range [...]string{"bearer", "token", "authorization", "password", "passwd", "secret", "apikey", "api_key", "api-key"} {
		if strings.Contains(l, kw) {
			return true
		}
	}
	return false
}

// ReplaceAttr is the slog ReplaceAttr hook applied to every attribute (and the built-in message) on
// every record. It (1) replaces the value of a sensitive-keyed attribute outright and (2) masks any
// secret embedded in a string value or the message. Group nodes are passed through untouched. Exported
// so other slog handlers can reuse the exact same redaction boundary (e.g. a test that wires the
// apiclient's opt-in body logging into a handler and asserts secrets are still masked — #126).
func ReplaceAttr(_ []string, a slog.Attr) slog.Attr {
	if a.Value.Kind() == slog.KindGroup {
		return a
	}
	if _, ok := sensitiveKeys[strings.ToLower(a.Key)]; ok {
		return slog.String(a.Key, Redacted)
	}
	if a.Value.Kind() == slog.KindString {
		if masked, changed := maskSecrets(a.Value.String()); changed {
			return slog.Attr{Key: a.Key, Value: slog.StringValue(masked)}
		}
	}
	return a
}
