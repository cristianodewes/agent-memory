package selfupdate

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

// --- fixtures -----------------------------------------------------------------------

type fakeAsset struct {
	name string
	data []byte
}

type fakeRelease struct {
	tag        string
	prerelease bool
	assets     []fakeAsset
}

// newReleaseServer serves a minimal GitHub Releases API for the given releases (newest
// first) plus a /dl/<name> endpoint for each asset's bytes.
func newReleaseServer(t *testing.T, owner, repo string, releases []fakeRelease) *httptest.Server {
	t.Helper()
	var srv *httptest.Server
	base := func() string { return srv.URL }

	assetJSON := func(a fakeAsset) map[string]any {
		return map[string]any{
			"name":                 a.name,
			"url":                  base() + "/dl/" + a.name,
			"browser_download_url": base() + "/dl/" + a.name,
			"size":                 len(a.data),
		}
	}
	releaseJSON := func(r fakeRelease) map[string]any {
		assets := make([]map[string]any, 0, len(r.assets))
		for _, a := range r.assets {
			assets = append(assets, assetJSON(a))
		}
		return map[string]any{
			"tag_name":   r.tag,
			"name":       r.tag,
			"draft":      false,
			"prerelease": r.prerelease,
			"assets":     assets,
		}
	}

	prefix := "/repos/" + owner + "/" + repo
	mux := http.NewServeMux()
	mux.HandleFunc(prefix+"/releases/latest", func(w http.ResponseWriter, _ *http.Request) {
		for _, rel := range releases {
			if !rel.prerelease {
				writeJSON(w, releaseJSON(rel))
				return
			}
		}
		http.Error(w, "not found", http.StatusNotFound)
	})
	mux.HandleFunc(prefix+"/releases", func(w http.ResponseWriter, _ *http.Request) {
		all := make([]map[string]any, 0, len(releases))
		for _, rel := range releases {
			all = append(all, releaseJSON(rel))
		}
		writeJSON(w, all)
	})
	mux.HandleFunc(prefix+"/releases/tags/", func(w http.ResponseWriter, r *http.Request) {
		tag := strings.TrimPrefix(r.URL.Path, prefix+"/releases/tags/")
		for _, rel := range releases {
			if rel.tag == tag {
				writeJSON(w, releaseJSON(rel))
				return
			}
		}
		http.Error(w, "not found", http.StatusNotFound)
	})
	mux.HandleFunc("/dl/", func(w http.ResponseWriter, r *http.Request) {
		name := strings.TrimPrefix(r.URL.Path, "/dl/")
		for _, rel := range releases {
			for _, a := range rel.assets {
				if a.name == name {
					_, _ = w.Write(a.data)
					return
				}
			}
		}
		http.Error(w, "not found", http.StatusNotFound)
	})

	srv = httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func buildTarGz(t *testing.T, innerName, content string) []byte {
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

func buildZip(t *testing.T, innerName, content string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	f, err := zw.Create(innerName)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.Write([]byte(content)); err != nil {
		t.Fatal(err)
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// stagedExec writes a fake "current" binary and returns its path.
func stagedExec(t *testing.T) string {
	t.Helper()
	exec := filepath.Join(t.TempDir(), "agent-memory")
	mustWrite(t, exec, "OLD-BINARY")
	return exec
}

func newTestUpdater(t *testing.T, apiURL string, opts Options) *Updater {
	t.Helper()
	opts.APIBaseURL = apiURL
	u, err := New(opts)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return u
}

// linuxRelease builds a release whose linux/amd64 tar.gz holds content, with a matching
// checksums.txt.
func linuxRelease(t *testing.T, version, content string) (fakeRelease, string) {
	t.Helper()
	archive := buildTarGz(t, innerBinaryName("linux"), content)
	assetName := assetFileName("linux", "amd64", version)
	sums := sha256Hex(archive) + "  " + assetName + "\n"
	return fakeRelease{
		tag: "v" + version,
		assets: []fakeAsset{
			{name: assetName, data: archive},
			{name: checksumsFileName, data: []byte(sums)},
		},
	}, sums
}

// --- tests --------------------------------------------------------------------------

func TestApplySuccess(t *testing.T) {
	const version, content = "1.5.0", "BRAND-NEW-BINARY-BYTES"
	rel, _ := linuxRelease(t, version, content)
	srv := newReleaseServer(t, "o", "r", []fakeRelease{rel})
	exec := stagedExec(t)

	u := newTestUpdater(t, srv.URL, Options{
		Owner: "o", Repo: "r", CurrentVersion: "1.0.0",
		GOOS: "linux", GOARCH: "amd64", ExecPath: exec, HTTPClient: srv.Client(),
	})

	plan, err := u.Check(context.Background())
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if plan.UpToDate || plan.TargetVersion != version {
		t.Fatalf("plan = %+v, want target %s and not up to date", plan, version)
	}

	var out bytes.Buffer
	if err := u.Apply(context.Background(), &out, plan); err != nil {
		t.Fatalf("Apply: %v", err)
	}
	if got := readFile(t, exec); got != content {
		t.Fatalf("binary content after update = %q, want %q", got, content)
	}
	for _, want := range []string{"Checksum verificado", "Atualizado para 1.5.0"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("output missing %q; got:\n%s", want, out.String())
		}
	}
}

func TestApplyChecksumMismatchAborts(t *testing.T) {
	const version, content = "1.5.0", "NEW"
	rel, _ := linuxRelease(t, version, content)
	// Corrupt the checksums.txt so verification fails.
	for i, a := range rel.assets {
		if a.name == checksumsFileName {
			bad := "deadbeef" + strings.Repeat("0", 56) + "  " + assetFileName("linux", "amd64", version) + "\n"
			rel.assets[i].data = []byte(bad)
		}
	}
	srv := newReleaseServer(t, "o", "r", []fakeRelease{rel})
	exec := stagedExec(t)

	u := newTestUpdater(t, srv.URL, Options{
		Owner: "o", Repo: "r", CurrentVersion: "1.0.0",
		GOOS: "linux", GOARCH: "amd64", ExecPath: exec, HTTPClient: srv.Client(),
	})
	plan, err := u.Check(context.Background())
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	err = u.Apply(context.Background(), &bytes.Buffer{}, plan)
	if err == nil || !strings.Contains(err.Error(), "checksum não confere") {
		t.Fatalf("expected a checksum mismatch error, got %v", err)
	}
	if got := readFile(t, exec); got != "OLD-BINARY" {
		t.Fatalf("binary must be untouched on checksum mismatch, got %q", got)
	}
}

func TestCheckUpToDateNoOp(t *testing.T) {
	const version = "1.5.0"
	rel, _ := linuxRelease(t, version, "whatever")
	srv := newReleaseServer(t, "o", "r", []fakeRelease{rel})
	exec := stagedExec(t)

	u := newTestUpdater(t, srv.URL, Options{
		Owner: "o", Repo: "r", CurrentVersion: version, // already current
		GOOS: "linux", GOARCH: "amd64", ExecPath: exec, HTTPClient: srv.Client(),
	})
	plan, err := u.Check(context.Background())
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if !plan.UpToDate {
		t.Fatalf("expected up-to-date plan, got %+v", plan)
	}
	var out bytes.Buffer
	if err := u.Apply(context.Background(), &out, plan); err != nil {
		t.Fatalf("Apply (no-op): %v", err)
	}
	if got := readFile(t, exec); got != "OLD-BINARY" {
		t.Fatalf("no-op must not touch the binary, got %q", got)
	}
	if !strings.Contains(out.String(), "nada a fazer") {
		t.Errorf("expected an informative no-op message, got %q", out.String())
	}
}

func TestPinnedVersionAppliesEvenDowngrade(t *testing.T) {
	const pinned, content = "1.4.0", "PINNED-OLDER-BUILD"
	rel, _ := linuxRelease(t, pinned, content)
	srv := newReleaseServer(t, "o", "r", []fakeRelease{rel})
	exec := stagedExec(t)

	u := newTestUpdater(t, srv.URL, Options{
		Owner: "o", Repo: "r", CurrentVersion: "1.5.0", PinnedVersion: pinned,
		GOOS: "linux", GOARCH: "amd64", ExecPath: exec, HTTPClient: srv.Client(),
	})
	plan, err := u.Check(context.Background())
	if err != nil {
		t.Fatalf("Check (pinned): %v", err)
	}
	if plan.UpToDate || plan.TargetVersion != pinned {
		t.Fatalf("pinned plan = %+v, want target %s applied", plan, pinned)
	}
	if err := u.Apply(context.Background(), &bytes.Buffer{}, plan); err != nil {
		t.Fatalf("Apply (pinned): %v", err)
	}
	if got := readFile(t, exec); got != content {
		t.Fatalf("pinned downgrade not applied, got %q", got)
	}
}

func TestEdgeChannelPicksNewestPrerelease(t *testing.T) {
	stable, _ := linuxRelease(t, "1.5.0", "stable")
	edge, _ := linuxRelease(t, "2.0.0-rc1", "edge")
	edge.prerelease = true
	// Server returns newest first: edge prerelease, then stable.
	srv := newReleaseServer(t, "o", "r", []fakeRelease{edge, stable})
	exec := stagedExec(t)

	u := newTestUpdater(t, srv.URL, Options{
		Owner: "o", Repo: "r", CurrentVersion: "1.5.0", Channel: ChannelEdge,
		GOOS: "linux", GOARCH: "amd64", ExecPath: exec, HTTPClient: srv.Client(),
	})
	plan, err := u.Check(context.Background())
	if err != nil {
		t.Fatalf("Check (edge): %v", err)
	}
	if plan.UpToDate || plan.TargetVersion != "2.0.0-rc1" {
		t.Fatalf("edge plan = %+v, want target 2.0.0-rc1", plan)
	}
}

func TestCheckErrorsWhenNoAssetForPlatform(t *testing.T) {
	rel, _ := linuxRelease(t, "1.5.0", "x") // only linux/amd64 asset present
	srv := newReleaseServer(t, "o", "r", []fakeRelease{rel})

	u := newTestUpdater(t, srv.URL, Options{
		Owner: "o", Repo: "r", CurrentVersion: "1.0.0",
		GOOS: "linux", GOARCH: "arm64", // no asset for this arch
		ExecPath: stagedExec(t), HTTPClient: srv.Client(),
	})
	_, err := u.Check(context.Background())
	if err == nil || !strings.Contains(err.Error(), "não traz um artefato") {
		t.Fatalf("expected a missing-asset error, got %v", err)
	}
}

func TestExtractFromZipFindsExe(t *testing.T) {
	data := buildZip(t, "agent-memory.exe", "WIN-BINARY")
	dir := t.TempDir()
	path, err := extractBinary(data, "agent-memory_1.0.0_windows_amd64.zip", dir, "agent-memory.exe")
	if err != nil {
		t.Fatalf("extractBinary(zip): %v", err)
	}
	if got := readFile(t, path); got != "WIN-BINARY" {
		t.Fatalf("extracted content = %q, want WIN-BINARY", got)
	}
}

func TestExtractErrorsWhenBinaryAbsent(t *testing.T) {
	data := buildTarGz(t, "some-other-file", "x")
	_, err := extractBinary(data, "agent-memory_1.0.0_linux_amd64.tar.gz", t.TempDir(), "agent-memory")
	if err == nil || !strings.Contains(err.Error(), "não encontrado") {
		t.Fatalf("expected a not-found error, got %v", err)
	}
}
