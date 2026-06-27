package log

import (
	"log/slog"
	"strings"
	"testing"
)

func TestMaskSecrets(t *testing.T) {
	cases := []struct {
		name      string
		in        string
		wantMask  bool
		mustHave  string // substring that must remain (or "")
		mustNotHv string // substring that must be gone (or "")
	}{
		{"authorization header", "Authorization: Bearer abc.def.ghi", true, Redacted, "abc.def.ghi"},
		{"bearer scheme", "got Bearer sk-1234567890 back", true, Redacted, "sk-1234567890"},
		{"token assignment", "token=sk-supersecret", true, Redacted, "sk-supersecret"},
		{"json token", `{"token":"sk-xyz"}`, true, Redacted, "sk-xyz"},
		{"query access_token", "GET /x?access_token=zzz", true, Redacted, "zzz"},
		{"plain prose", "drain complete sent=3", false, "drain complete sent=3", ""},
		{"path only", "POST /hook/batch", false, "/hook/batch", ""},
		{"tokens word not matched", "tokens used: 5", false, "tokens used: 5", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, changed := maskSecrets(c.in)
			if changed != c.wantMask {
				t.Fatalf("maskSecrets(%q) changed=%v, want %v (got %q)", c.in, changed, c.wantMask, got)
			}
			if c.mustHave != "" && !strings.Contains(got, c.mustHave) {
				t.Fatalf("masked %q = %q, want to contain %q", c.in, got, c.mustHave)
			}
			if c.mustNotHv != "" && strings.Contains(got, c.mustNotHv) {
				t.Fatalf("masked %q = %q, must NOT contain secret %q", c.in, got, c.mustNotHv)
			}
		})
	}
}

func TestReplaceAttrRedactsSensitiveKeys(t *testing.T) {
	for _, key := range []string{"authorization", "Authorization", "token", "TOKEN", "password", "api_key", "access_token"} {
		a := ReplaceAttr(nil, slog.String(key, "the-actual-secret"))
		if a.Value.String() != Redacted {
			t.Fatalf("key %q not redacted: got %q", key, a.Value.String())
		}
	}
}

func TestReplaceAttrLeavesOrdinaryAttrs(t *testing.T) {
	a := ReplaceAttr(nil, slog.Int("sent", 3))
	if a.Value.Kind() != slog.KindInt64 || a.Value.Int64() != 3 {
		t.Fatalf("ordinary int attr altered: %+v", a)
	}
	a = ReplaceAttr(nil, slog.String("kind", "PostToolUse"))
	if a.Value.String() != "PostToolUse" {
		t.Fatalf("ordinary string attr altered: %q", a.Value.String())
	}
}

func TestReplaceAttrMasksSecretInStringValue(t *testing.T) {
	// A non-sensitive KEY whose VALUE embeds a secret (e.g. an error message) must still be masked.
	a := ReplaceAttr(nil, slog.String("err", "POST failed: Authorization: Bearer leaky-token-123"))
	if strings.Contains(a.Value.String(), "leaky-token-123") {
		t.Fatalf("secret leaked through a non-sensitive key: %q", a.Value.String())
	}
	if !strings.Contains(a.Value.String(), Redacted) {
		t.Fatalf("expected redaction marker, got %q", a.Value.String())
	}
}
