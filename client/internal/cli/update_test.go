package cli

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/cristianodewes/agent-memory/client/internal/selfupdate"
)

func TestRootHasUpdateCommand(t *testing.T) {
	update := findSubcommand(newRootCmd(), "update")
	if update == nil {
		t.Fatal("expected an `update` subcommand on the root command")
	}
	var hasAlias bool
	for _, a := range update.Aliases {
		if a == "self-update" {
			hasAlias = true
		}
	}
	if !hasAlias {
		t.Fatalf("expected `update` to alias `self-update`, aliases = %v", update.Aliases)
	}
}

func TestUpdateInvalidChannel(t *testing.T) {
	cmd := newUpdateCmd()
	cmd.SetArgs([]string{"--channel", "bogus"})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "canal inválido") {
		t.Fatalf("expected an invalid-channel error, got %v", err)
	}
}

// TestUpdateCommandCheck drives the cobra command end to end with --check against a mock
// Releases API. The embedded Version defaults to the dev placeholder, so any real release
// is newer and reported as available — without mutating global state.
func TestUpdateCommandCheck(t *testing.T) {
	srv := newLatestServer(t, "v1.9.0", map[string][]byte{
		hostAssetName("1.9.0"): {},
		"checksums.txt":        {},
	})

	var out bytes.Buffer
	cmd := newUpdateCmd()
	cmd.SetArgs([]string{"--check", "--api-url", srv.URL})
	cmd.SetOut(&out)
	cmd.SetErr(io.Discard)
	if err := cmd.Execute(); err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !strings.Contains(out.String(), "→ 1.9.0") {
		t.Fatalf("expected an available-update report, got %q", out.String())
	}
}

func TestRunUpdateAppliesViaServer(t *testing.T) {
	const version, content = "1.9.0", "FRESH-CLIENT-BINARY"
	archive := buildTarGzCLI(t, "agent-memory", content)
	assetName := fmt.Sprintf("agent-memory_%s_linux_amd64.tar.gz", version)
	sums := sha256HexCLI(archive) + "  " + assetName + "\n"

	srv := newLatestServer(t, "v"+version, map[string][]byte{
		assetName:       archive,
		"checksums.txt": []byte(sums),
	})

	exec := filepath.Join(t.TempDir(), "agent-memory")
	if err := os.WriteFile(exec, []byte("OLD"), 0o755); err != nil {
		t.Fatal(err)
	}

	u, err := selfupdate.New(selfupdate.Options{
		CurrentVersion: "1.0.0",
		GOOS:           "linux",
		GOARCH:         "amd64",
		ExecPath:       exec,
		APIBaseURL:     srv.URL,
		HTTPClient:     srv.Client(),
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	var out bytes.Buffer
	if err := runUpdate(context.Background(), &out, u, false); err != nil {
		t.Fatalf("runUpdate: %v", err)
	}
	if got, _ := os.ReadFile(exec); string(got) != content {
		t.Fatalf("binary not replaced: got %q", string(got))
	}
	if !strings.Contains(out.String(), "Atualizado para 1.9.0") {
		t.Errorf("expected an applied-update message, got %q", out.String())
	}
}

func TestRunUpdateRefusesPackageManager(t *testing.T) {
	// A path under a scoop install must be detected and left untouched.
	managed := filepath.ToSlash(filepath.Join(t.TempDir(), "scoop", "apps", "agent-memory", "agent-memory"))
	if err := os.MkdirAll(filepath.Dir(managed), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(managed, []byte("OLD"), 0o755); err != nil {
		t.Fatal(err)
	}

	srv := newLatestServer(t, "v1.9.0", map[string][]byte{
		"agent-memory_1.9.0_linux_amd64.tar.gz": []byte("ignored"),
		"checksums.txt":                         []byte("ignored"),
	})
	u, err := selfupdate.New(selfupdate.Options{
		CurrentVersion: "1.0.0", GOOS: "linux", GOARCH: "amd64",
		ExecPath: managed, APIBaseURL: srv.URL, HTTPClient: srv.Client(),
	})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	var out bytes.Buffer
	if err := runUpdate(context.Background(), &out, u, false); err != nil {
		t.Fatalf("runUpdate (managed): %v", err)
	}
	if !strings.Contains(out.String(), "Scoop") || !strings.Contains(out.String(), "scoop update agent-memory") {
		t.Fatalf("expected a Scoop guidance message, got %q", out.String())
	}
	if got, _ := os.ReadFile(managed); string(got) != "OLD" {
		t.Fatalf("managed binary must not be overwritten, got %q", string(got))
	}
}

// --- helpers ------------------------------------------------------------------------

func hostAssetName(version string) string {
	ext := "tar.gz"
	if runtime.GOOS == "windows" {
		ext = "zip"
	}
	return fmt.Sprintf("agent-memory_%s_%s_%s.%s", version, runtime.GOOS, runtime.GOARCH, ext)
}

// newLatestServer serves GET /repos/cristianodewes/agent-memory/releases/latest and a
// /dl/<name> endpoint for each asset's bytes.
func newLatestServer(t *testing.T, tag string, assets map[string][]byte) *httptest.Server {
	t.Helper()
	var srv *httptest.Server
	mux := http.NewServeMux()
	mux.HandleFunc("/repos/cristianodewes/agent-memory/releases/latest",
		func(w http.ResponseWriter, _ *http.Request) {
			list := make([]map[string]any, 0, len(assets))
			for name := range assets {
				list = append(list, map[string]any{
					"name":                 name,
					"url":                  srv.URL + "/dl/" + name,
					"browser_download_url": srv.URL + "/dl/" + name,
				})
			}
			writeJSONResp(w, http.StatusOK, map[string]any{"tag_name": tag, "assets": list})
		})
	mux.HandleFunc("/dl/", func(w http.ResponseWriter, r *http.Request) {
		name := strings.TrimPrefix(r.URL.Path, "/dl/")
		if data, ok := assets[name]; ok {
			_, _ = w.Write(data)
			return
		}
		http.Error(w, "not found", http.StatusNotFound)
	})
	srv = httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func buildTarGzCLI(t *testing.T, innerName, content string) []byte {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	hdr := &tar.Header{Name: innerName, Mode: 0o755, Size: int64(len(content)), Typeflag: tar.TypeReg}
	if err := tw.WriteHeader(hdr); err != nil {
		t.Fatal(err)
	}
	if _, err := tw.Write([]byte(content)); err != nil {
		t.Fatal(err)
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func sha256HexCLI(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}
