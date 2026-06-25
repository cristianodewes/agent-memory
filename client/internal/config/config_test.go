package config

import "testing"

func TestWithIdentityOverrides(t *testing.T) {
	base := Config{ServerURL: "http://127.0.0.1:8080", Token: "env-token", DataDir: "/data"}

	t.Run("non-empty values override", func(t *testing.T) {
		got := base.WithIdentityOverrides("http://10.0.0.5:8080", "marker-token")
		if got.ServerURL != "http://10.0.0.5:8080" {
			t.Fatalf("serverURL = %q, want overridden", got.ServerURL)
		}
		if got.Token != "marker-token" {
			t.Fatalf("token = %q, want overridden", got.Token)
		}
		if got.DataDir != "/data" {
			t.Fatalf("dataDir = %q, want unchanged", got.DataDir)
		}
	})

	t.Run("empty values leave fields untouched", func(t *testing.T) {
		got := base.WithIdentityOverrides("   ", "")
		if got.ServerURL != base.ServerURL || got.Token != base.Token {
			t.Fatalf("blank overrides changed config: %+v", got)
		}
	})

	t.Run("only server url overridden, token kept", func(t *testing.T) {
		got := base.WithIdentityOverrides("http://h:9", "")
		if got.ServerURL != "http://h:9" {
			t.Fatalf("serverURL = %q, want overridden", got.ServerURL)
		}
		if got.Token != "env-token" {
			t.Fatalf("token = %q, want env-token kept", got.Token)
		}
	})
}
