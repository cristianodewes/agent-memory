package openaioauth

import (
	"encoding/json"
	"os"
	"testing"
)

func TestSaveLoadRoundTrip(t *testing.T) {
	dir := t.TempDir()
	want := Token{Access: "acc", Refresh: "ref", Expires: 1730000000000, AccountID: "acct-1"}
	if err := Save(dir, want); err != nil {
		t.Fatalf("Save: %v", err)
	}
	got, ok, err := Load(dir)
	if err != nil || !ok {
		t.Fatalf("Load: ok=%v err=%v", ok, err)
	}
	if got != want {
		t.Fatalf("round trip mismatch: got %+v want %+v", got, want)
	}
}

func TestPreservesOtherProviderEntries(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(TokenPath(dir),
		[]byte(`{"copilot":{"type":"oauth","access":"x"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Save(dir, Token{Access: "acc", Refresh: "ref", Expires: 1}); err != nil {
		t.Fatalf("Save: %v", err)
	}
	var root map[string]json.RawMessage
	data, _ := os.ReadFile(TokenPath(dir))
	if err := json.Unmarshal(data, &root); err != nil {
		t.Fatal(err)
	}
	if _, ok := root["copilot"]; !ok {
		t.Fatal("expected the copilot entry to be preserved")
	}
	if _, ok := root["openai"]; !ok {
		t.Fatal("expected the openai entry to be written")
	}
}

func TestDeleteRemovesOnlyOpenAI(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(TokenPath(dir),
		[]byte(`{"openai":{"type":"oauth","access":"a","refresh":"r","expires":1},`+
			`"copilot":{"type":"oauth","access":"x"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := Delete(dir); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	var root map[string]json.RawMessage
	data, _ := os.ReadFile(TokenPath(dir))
	if err := json.Unmarshal(data, &root); err != nil {
		t.Fatal(err)
	}
	if _, ok := root["openai"]; ok {
		t.Fatal("openai entry should be removed")
	}
	if _, ok := root["copilot"]; !ok {
		t.Fatal("copilot entry should be preserved")
	}
}

func TestLoadAbsentIsNotLoggedIn(t *testing.T) {
	_, ok, err := Load(t.TempDir())
	if err != nil {
		t.Fatalf("Load on absent file errored: %v", err)
	}
	if ok {
		t.Fatal("expected ok=false for an absent token file")
	}
}

func TestIgnoresNonOauthEntry(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(TokenPath(dir),
		[]byte(`{"openai":{"type":"api","key":"sk-test"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, ok, _ := Load(dir); ok {
		t.Fatal("a non-oauth openai entry must not load as an OAuth token")
	}
}
