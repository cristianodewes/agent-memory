// Package selfupdate implements the agent-memory client's in-place self-update: it
// queries the project's GitHub Releases, compares the latest published version with the
// version embedded in the running binary (internal/cli.Version, stamped via goreleaser
// ldflags), downloads the archive matching the current OS/arch, verifies it against the
// release's checksums.txt, and atomically replaces the running binary with rollback.
//
// The package is intentionally cobra-free so every step (release resolution, checksum
// verification, archive extraction, the atomic replace and the package-manager
// heuristic) is unit-testable; internal/cli/update.go is only a thin wiring layer.
//
// It NEVER auto-updates: nothing here runs unless the user invokes `agent-memory update`.
package selfupdate

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const (
	defaultOwner = "cristianodewes"
	defaultRepo  = "agent-memory"

	// defaultAPIBaseURL is the GitHub REST API root. Options.APIBaseURL overrides it so
	// tests can point release lookups at an httptest server.
	defaultAPIBaseURL = "https://api.github.com"

	// githubAPIVersion is the REST API version GitHub recommends pinning.
	githubAPIVersion = "2022-11-28"

	// maxJSONResponse and maxAssetSize bound what we read from the network so a hostile
	// or buggy server cannot exhaust memory. Release archives are a few MB; 200 MiB is
	// comfortably above that.
	maxJSONResponse = 8 << 20
	maxAssetSize    = 200 << 20
)

// Channel selects which published releases the updater considers.
type Channel string

const (
	// ChannelStable tracks the latest non-prerelease GitHub Release (releases/latest).
	ChannelStable Channel = "stable"
	// ChannelEdge tracks the newest published release including prereleases, for users
	// who opt into the edge stream produced by the CD pipeline (#106).
	ChannelEdge Channel = "edge"
)

// Options configures an Updater. Empty fields fall back to sensible defaults in New.
type Options struct {
	Owner          string       // GitHub repo owner; default "cristianodewes"
	Repo           string       // GitHub repo name; default "agent-memory"
	CurrentVersion string       // version embedded in the running binary (cli.Version)
	Channel        Channel      // ChannelStable (default) or ChannelEdge
	PinnedVersion  string       // explicit target version ("" = latest for the channel)
	GOOS           string       // target OS; default runtime.GOOS
	GOARCH         string       // target arch; default runtime.GOARCH
	ExecPath       string       // path of the running binary; default os.Executable()
	APIBaseURL     string       // GitHub API root; default defaultAPIBaseURL
	Token          string       // GitHub token for private-repo asset access ("" = anon)
	HTTPClient     *http.Client // default a 60s-timeout client
}

// Updater resolves and applies self-updates. Build one with New.
type Updater struct {
	owner          string
	repo           string
	currentVersion string
	channel        Channel
	pinnedVersion  string
	goos           string
	goarch         string
	execPath       string
	apiBaseURL     string
	token          string
	httpClient     *http.Client
}

// New builds an Updater from opts, applying defaults and resolving the running binary's
// real path (following symlinks so package-manager detection and the atomic replace act
// on the concrete file).
func New(opts Options) (*Updater, error) {
	u := &Updater{
		owner:          firstNonEmpty(opts.Owner, defaultOwner),
		repo:           firstNonEmpty(opts.Repo, defaultRepo),
		currentVersion: strings.TrimSpace(opts.CurrentVersion),
		channel:        opts.Channel,
		pinnedVersion:  strings.TrimSpace(opts.PinnedVersion),
		goos:           firstNonEmpty(opts.GOOS, runtime.GOOS),
		goarch:         firstNonEmpty(opts.GOARCH, runtime.GOARCH),
		execPath:       opts.ExecPath,
		apiBaseURL:     strings.TrimRight(firstNonEmpty(opts.APIBaseURL, defaultAPIBaseURL), "/"),
		token:          strings.TrimSpace(opts.Token),
		httpClient:     opts.HTTPClient,
	}
	if u.channel == "" {
		u.channel = ChannelStable
	}
	if u.httpClient == nil {
		u.httpClient = &http.Client{Timeout: 60 * time.Second}
	}
	if u.execPath == "" {
		exe, err := os.Executable()
		if err != nil {
			return nil, fmt.Errorf("não foi possível localizar o binário em execução: %w", err)
		}
		u.execPath = exe
	}
	if resolved, err := filepath.EvalSymlinks(u.execPath); err == nil {
		u.execPath = resolved
	}
	return u, nil
}

// ExecPath returns the resolved path of the binary the updater would replace.
func (u *Updater) ExecPath() string { return u.execPath }

// Plan describes the outcome of a release lookup: what is installed, what would be
// installed, and whether any action is needed.
type Plan struct {
	CurrentVersion string // running binary's version
	TargetVersion  string // release version (tag without a leading "v") we would install
	TargetTag      string // the exact release tag (e.g. "v1.2.0")
	AssetName      string // archive asset name for this OS/arch
	UpToDate       bool   // the running binary already satisfies the target

	asset     asset // resolved archive asset
	checksums asset // resolved checksums.txt asset
}

// Check resolves the target release for the configured channel/pin and reports whether
// an update is available. It performs no writes and downloads no archive.
func (u *Updater) Check(ctx context.Context) (*Plan, error) {
	rel, err := u.resolveRelease(ctx)
	if err != nil {
		return nil, err
	}
	targetVersion := strings.TrimPrefix(rel.TagName, "v")
	assetName := assetFileName(u.goos, u.goarch, targetVersion)

	bin, ok := rel.findAsset(assetName)
	if !ok {
		return nil, fmt.Errorf("o release %s não traz um artefato para %s/%s (esperado %q); "+
			"artefatos disponíveis: %s", rel.TagName, u.goos, u.goarch, assetName, rel.assetNames())
	}
	sums, ok := rel.findAsset(checksumsFileName)
	if !ok {
		return nil, fmt.Errorf("o release %s não traz %s para validação de checksum",
			rel.TagName, checksumsFileName)
	}

	cmp := compareVersions(targetVersion, u.currentVersion)
	upToDate := cmp <= 0
	if u.pinnedVersion != "" {
		// An explicit --version is an exact pin: only a byte-for-byte version match is a
		// no-op; anything else (including a downgrade) is applied on demand.
		upToDate = cmp == 0
	}

	return &Plan{
		CurrentVersion: u.currentVersion,
		TargetVersion:  targetVersion,
		TargetTag:      rel.TagName,
		AssetName:      assetName,
		UpToDate:       upToDate,
		asset:          bin,
		checksums:      sums,
	}, nil
}

// Apply downloads the planned archive, verifies its checksum, extracts the binary and
// replaces the running executable atomically (with rollback). It is a no-op when the
// plan is already up to date. Progress is written to out.
//
// Order matters for safety: the checksum is verified BEFORE anything is extracted or the
// live binary is touched, so a corrupt or tampered download aborts without side effects.
func (u *Updater) Apply(ctx context.Context, out io.Writer, plan *Plan) error {
	if plan.UpToDate {
		fmt.Fprintf(out, "Já está na versão mais recente (%s); nada a fazer.\n", plan.CurrentVersion)
		return nil
	}

	fmt.Fprintf(out, "Baixando agent-memory %s (%s)...\n", plan.TargetVersion, plan.AssetName)
	archive, err := u.downloadAsset(ctx, plan.asset)
	if err != nil {
		return err
	}
	sumsText, err := u.downloadAsset(ctx, plan.checksums)
	if err != nil {
		return err
	}

	want, ok := parseChecksums(string(sumsText))[plan.AssetName]
	if !ok {
		return fmt.Errorf("%s não está listado em %s; abortando sem alterar o binário",
			plan.AssetName, checksumsFileName)
	}
	got := sha256Hex(archive)
	if got != want {
		return fmt.Errorf("checksum não confere para %s (esperado %s, obtido %s); "+
			"binário não foi alterado", plan.AssetName, want, got)
	}
	fmt.Fprintln(out, "Checksum verificado.")

	destDir := filepath.Dir(u.execPath)
	staged, err := extractBinary(archive, plan.AssetName, destDir, innerBinaryName(u.goos))
	if err != nil {
		return err
	}
	// From here, any failure must not leave a stray staged binary behind.
	defer func() {
		if _, statErr := os.Stat(staged); statErr == nil {
			_ = os.Remove(staged)
		}
	}()

	if u.goos != "windows" {
		if err := os.Chmod(staged, 0o755); err != nil {
			return fmt.Errorf("ajustando permissões do novo binário: %w", err)
		}
	}
	if err := replaceBinary(u.execPath, staged); err != nil {
		return err
	}

	fmt.Fprintf(out, "Atualizado para %s em %s.\n", plan.TargetVersion, u.execPath)
	return nil
}

// resolveRelease fetches the release the channel/pin points at.
func (u *Updater) resolveRelease(ctx context.Context) (release, error) {
	switch {
	case u.pinnedVersion != "":
		tag := u.pinnedVersion
		if !strings.HasPrefix(tag, "v") {
			tag = "v" + tag
		}
		return u.getRelease(ctx, u.repoPath("/releases/tags/"+tag))
	case u.channel == ChannelEdge:
		return u.latestEdgeRelease(ctx)
	default:
		return u.getRelease(ctx, u.repoPath("/releases/latest"))
	}
}

// latestEdgeRelease returns the newest published (non-draft) release, including
// prereleases — the GitHub list endpoint returns releases newest-first.
func (u *Updater) latestEdgeRelease(ctx context.Context) (release, error) {
	body, err := u.apiGET(ctx, u.repoPath("/releases?per_page=20"))
	if err != nil {
		return release{}, err
	}
	var rels []release
	if err := json.Unmarshal(body, &rels); err != nil {
		return release{}, fmt.Errorf("decodificando lista de releases: %w", err)
	}
	for _, r := range rels {
		if !r.Draft {
			return r, nil
		}
	}
	return release{}, fmt.Errorf("nenhum release publicado encontrado no canal edge")
}

func (u *Updater) getRelease(ctx context.Context, pathPart string) (release, error) {
	body, err := u.apiGET(ctx, pathPart)
	if err != nil {
		return release{}, err
	}
	var r release
	if err := json.Unmarshal(body, &r); err != nil {
		return release{}, fmt.Errorf("decodificando release: %w", err)
	}
	return r, nil
}

func (u *Updater) repoPath(suffix string) string {
	return "/repos/" + u.owner + "/" + u.repo + suffix
}

// apiGET performs a GET against the GitHub REST API and returns the (bounded) body.
func (u *Updater) apiGET(ctx context.Context, pathPart string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.apiBaseURL+pathPart, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("X-GitHub-Api-Version", githubAPIVersion)
	u.setCommonHeaders(req)

	resp, err := u.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("consultando %s: %w", pathPart, err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxJSONResponse))
	if err != nil {
		return nil, fmt.Errorf("lendo resposta de %s: %w", pathPart, err)
	}
	switch {
	case resp.StatusCode == http.StatusNotFound:
		return nil, fmt.Errorf("release não encontrado em %s: verifique a versão/canal informados", pathPart)
	case resp.StatusCode != http.StatusOK:
		return nil, fmt.Errorf("a API do GitHub respondeu %s para %s: %s",
			resp.Status, pathPart, strings.TrimSpace(string(body)))
	}
	return body, nil
}

// downloadAsset fetches one release asset's raw bytes. It uses the asset's API URL with
// an octet-stream Accept (which also works for private repos when a token is set),
// falling back to the public browser_download_url.
func (u *Updater) downloadAsset(ctx context.Context, a asset) ([]byte, error) {
	downloadURL := a.URL
	if downloadURL == "" {
		downloadURL = a.BrowserDownloadURL
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, downloadURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/octet-stream")
	u.setCommonHeaders(req)

	resp, err := u.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("baixando %s: %w", a.Name, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("baixando %s: servidor respondeu %s", a.Name, resp.Status)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxAssetSize))
	if err != nil {
		return nil, fmt.Errorf("lendo %s: %w", a.Name, err)
	}
	return data, nil
}

func (u *Updater) setCommonHeaders(req *http.Request) {
	req.Header.Set("User-Agent", "agent-memory-selfupdate/"+firstNonEmpty(u.currentVersion, "dev"))
	if u.token != "" {
		req.Header.Set("Authorization", "Bearer "+u.token)
	}
}

// release is the subset of a GitHub Release we consume.
type release struct {
	TagName    string  `json:"tag_name"`
	Name       string  `json:"name"`
	Draft      bool    `json:"draft"`
	Prerelease bool    `json:"prerelease"`
	Assets     []asset `json:"assets"`
}

// asset is one downloadable file attached to a release. URL is the API asset URL (raw
// bytes with Accept: application/octet-stream); BrowserDownloadURL is the public CDN URL.
type asset struct {
	Name               string `json:"name"`
	URL                string `json:"url"`
	BrowserDownloadURL string `json:"browser_download_url"`
	Size               int64  `json:"size"`
}

func (r release) findAsset(want string) (asset, bool) {
	for _, a := range r.Assets {
		if a.Name == want {
			return a, true
		}
	}
	return asset{}, false
}

func (r release) assetNames() string {
	names := make([]string, 0, len(r.Assets))
	for _, a := range r.Assets {
		names = append(names, a.Name)
	}
	if len(names) == 0 {
		return "(nenhum)"
	}
	return strings.Join(names, ", ")
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}
