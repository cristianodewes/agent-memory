package drain

import (
	"bytes"
	"context"
	"log/slog"
	"strings"
	"testing"
)

// TestDrainLogsBatchOutcome proves the injected Options.Logger receives the per-batch ship record
// (issue #117: the logger is injected into internal/drain).
func TestDrainLogsBatchOutcome(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 3)

	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	d := New(s, &fakePoster{}, Options{Logger: logger, Sleep: noSleep})

	res, err := d.Drain(context.Background())
	if err != nil {
		t.Fatalf("drain: %v", err)
	}
	if res.Sent != 3 {
		t.Fatalf("sent = %d, want 3", res.Sent)
	}
	if got := buf.String(); !strings.Contains(got, "batch shipped") {
		t.Fatalf("expected a 'batch shipped' record from the injected logger, got:\n%s", got)
	}
}

// TestDrainNilLoggerIsSafe ensures an Options without a Logger falls back to a no-op (no nil-deref),
// so existing callers are unaffected by the new field.
func TestDrainNilLoggerIsSafe(t *testing.T) {
	s := newSpool(t)
	appendN(t, s, 1)

	d := New(s, &fakePoster{}, Options{})
	if _, err := d.Drain(context.Background()); err != nil {
		t.Fatalf("drain with nil logger: %v", err)
	}
}
