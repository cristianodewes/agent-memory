package hook

import (
	"strings"
	"testing"
)

func TestSessionStartAdditionalContext(t *testing.T) {
	b, ok := SessionStartAdditionalContext("where you left off")
	if !ok {
		t.Fatal("expected ok=true for non-empty context")
	}
	s := string(b)
	if !strings.Contains(s, `"hookEventName":"SessionStart"`) {
		t.Errorf("missing SessionStart hookEventName: %s", s)
	}
	if !strings.Contains(s, `"additionalContext":"where you left off"`) {
		t.Errorf("missing additionalContext: %s", s)
	}
	if _, ok := SessionStartAdditionalContext(""); ok {
		t.Error("empty context must be a clean no-op (ok=false)")
	}
}

func TestUserPromptSubmitAdditionalContext(t *testing.T) {
	b, ok := UserPromptSubmitAdditionalContext("## Relevant memory")
	if !ok {
		t.Fatal("expected ok=true for non-empty context")
	}
	s := string(b)
	if !strings.Contains(s, `"hookEventName":"UserPromptSubmit"`) {
		t.Errorf("missing UserPromptSubmit hookEventName: %s", s)
	}
	if !strings.Contains(s, `"additionalContext":"## Relevant memory"`) {
		t.Errorf("missing additionalContext: %s", s)
	}
	if _, ok := UserPromptSubmitAdditionalContext(""); ok {
		t.Error("empty context must be a clean no-op (ok=false)")
	}
}
