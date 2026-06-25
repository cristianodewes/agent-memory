package oidc

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// deviceGrantType is the RFC 8628 grant_type presented at the token endpoint while polling.
const deviceGrantType = "urn:ietf:params:oauth:grant-type:device_code"

// defaultPollInterval is used when the device-authorization response omits `interval` (RFC 8628 §3.2
// says clients SHOULD assume 5 seconds).
const defaultPollInterval = 5 * time.Second

// httpTimeout bounds each individual IdP request. The overall flow is bounded separately by the
// device code's expiry (and the caller's context).
const httpTimeout = 30 * time.Second

// Metadata is the subset of the OIDC/OAuth2 provider configuration the device grant needs, discovered
// from the issuer's `/.well-known/openid-configuration`. Either endpoint may be overridden explicitly
// (LoginRequest) for an IdP that does not publish discovery.
type Metadata struct {
	Issuer                      string `json:"issuer"`
	DeviceAuthorizationEndpoint string `json:"device_authorization_endpoint"`
	TokenEndpoint               string `json:"token_endpoint"`
}

// DeviceAuth is the RFC 8628 §3.2 device-authorization response: the codes and the URL the user visits
// to approve the grant, plus how long the codes live and how fast to poll.
type DeviceAuth struct {
	DeviceCode              string `json:"device_code"`
	UserCode                string `json:"user_code"`
	VerificationURI         string `json:"verification_uri"`
	VerificationURIComplete string `json:"verification_uri_complete,omitempty"`
	ExpiresIn               int    `json:"expires_in"`
	Interval                int    `json:"interval,omitempty"`
}

// pollInterval is the response's interval, or the RFC default when absent/non-positive.
func (d DeviceAuth) pollInterval() time.Duration {
	if d.Interval <= 0 {
		return defaultPollInterval
	}
	return time.Duration(d.Interval) * time.Second
}

// tokenResponse is the token endpoint's reply — either a granted token or an RFC 6749 / 8628 error
// (`error`=authorization_pending|slow_down|access_denied|expired_token|…).
type tokenResponse struct {
	AccessToken      string `json:"access_token"`
	TokenType        string `json:"token_type"`
	RefreshToken     string `json:"refresh_token"`
	IDToken          string `json:"id_token"`
	Scope            string `json:"scope"`
	ExpiresIn        int    `json:"expires_in"`
	Error            string `json:"error"`
	ErrorDescription string `json:"error_description"`
}

// LoginRequest configures one device-grant run.
type LoginRequest struct {
	Issuer   string // the IdP issuer URL (used for discovery and recorded in the credential)
	ClientID string // the public client id registered with the IdP for the device flow
	Scope    string // requested scopes (space-separated); "openid" is added if absent

	// Optional explicit endpoints for an IdP without discovery. When set, discovery is skipped.
	DeviceAuthEndpoint string
	TokenEndpoint      string
}

// Client runs the device grant. The clock and sleeper are injectable so the poll loop is fast and
// deterministic under test; production uses the real wall clock and a context-aware sleep.
type Client struct {
	http  *http.Client
	now   func() time.Time
	sleep func(context.Context, time.Duration) error
	out   io.Writer // where the user-code prompt is printed
}

// Option configures a Client.
type Option func(*Client)

// WithHTTPClient overrides the HTTP client (tests point it at an httptest server).
func WithHTTPClient(h *http.Client) Option { return func(c *Client) { c.http = h } }

// WithClock overrides the wall clock (tests freeze/advance time).
func WithClock(now func() time.Time) Option { return func(c *Client) { c.now = now } }

// WithSleeper overrides the poll sleep (tests make it a no-op so polling does not actually wait).
func WithSleeper(s func(context.Context, time.Duration) error) Option {
	return func(c *Client) { c.sleep = s }
}

// WithOutput overrides where the user-code instructions are printed (default os.Stderr via the CLI).
func WithOutput(w io.Writer) Option { return func(c *Client) { c.out = w } }

// NewClient builds a device-grant client with production defaults.
func NewClient(opts ...Option) *Client {
	c := &Client{
		http:  &http.Client{Timeout: httpTimeout},
		now:   time.Now,
		sleep: sleepCtx,
		out:   io.Discard,
	}
	for _, o := range opts {
		o(c)
	}
	return c
}

// Login runs the full RFC 8628 flow and returns the persisted-ready Credentials: discover (unless
// endpoints are pinned) → request a device code → print the verification URL + user code → poll the
// token endpoint until the user approves (or the code expires / is denied). The returned Credentials
// still need to be Save()d by the caller.
func (c *Client) Login(ctx context.Context, req LoginRequest) (Credentials, error) {
	if strings.TrimSpace(req.Issuer) == "" {
		return Credentials{}, errors.New("oidc: issuer is required")
	}
	if strings.TrimSpace(req.ClientID) == "" {
		return Credentials{}, errors.New("oidc: client-id is required")
	}

	meta, err := c.resolveMetadata(ctx, req)
	if err != nil {
		return Credentials{}, err
	}

	da, err := c.requestDeviceCode(ctx, meta, req)
	if err != nil {
		return Credentials{}, err
	}
	c.promptUser(da)

	tok, err := c.poll(ctx, meta, req.ClientID, da)
	if err != nil {
		return Credentials{}, err
	}

	now := c.now()
	creds := Credentials{
		Issuer:       req.Issuer,
		ClientID:     req.ClientID,
		Subject:      unverifiedSubject(tok.AccessToken),
		TokenType:    tok.TokenType,
		AccessToken:  tok.AccessToken,
		RefreshToken: tok.RefreshToken,
		IDToken:      tok.IDToken,
		Scope:        tok.Scope,
		ObtainedAt:   now,
	}
	if tok.ExpiresIn > 0 {
		creds.ExpiresAt = now.Add(time.Duration(tok.ExpiresIn) * time.Second)
	}
	return creds, nil
}

// resolveMetadata uses the explicit endpoints when both are pinned, otherwise discovers them.
func (c *Client) resolveMetadata(ctx context.Context, req LoginRequest) (Metadata, error) {
	if req.DeviceAuthEndpoint != "" && req.TokenEndpoint != "" {
		return Metadata{
			Issuer:                      req.Issuer,
			DeviceAuthorizationEndpoint: req.DeviceAuthEndpoint,
			TokenEndpoint:               req.TokenEndpoint,
		}, nil
	}
	meta, err := c.discover(ctx, req.Issuer)
	if err != nil {
		return Metadata{}, err
	}
	// Allow a partial override (e.g. an IdP that omits the device endpoint from discovery).
	if req.DeviceAuthEndpoint != "" {
		meta.DeviceAuthorizationEndpoint = req.DeviceAuthEndpoint
	}
	if req.TokenEndpoint != "" {
		meta.TokenEndpoint = req.TokenEndpoint
	}
	if meta.DeviceAuthorizationEndpoint == "" {
		return Metadata{}, fmt.Errorf(
			"oidc: issuer %q advertises no device_authorization_endpoint (pass --device-endpoint)", req.Issuer)
	}
	if meta.TokenEndpoint == "" {
		return Metadata{}, fmt.Errorf(
			"oidc: issuer %q advertises no token_endpoint (pass --token-endpoint)", req.Issuer)
	}
	return meta, nil
}

// discover fetches <issuer>/.well-known/openid-configuration (RFC 8414 / OIDC Discovery).
func (c *Client) discover(ctx context.Context, issuer string) (Metadata, error) {
	wellKnown := strings.TrimRight(issuer, "/") + "/.well-known/openid-configuration"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, wellKnown, nil)
	if err != nil {
		return Metadata{}, fmt.Errorf("oidc: build discovery request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return Metadata{}, fmt.Errorf("oidc: discovery request to %s: %w", wellKnown, err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return Metadata{}, fmt.Errorf("oidc: discovery %s returned %s", wellKnown, resp.Status)
	}
	var meta Metadata
	if err := json.NewDecoder(resp.Body).Decode(&meta); err != nil {
		return Metadata{}, fmt.Errorf("oidc: decode discovery document: %w", err)
	}
	return meta, nil
}

// requestDeviceCode performs the RFC 8628 §3.1 device-authorization request.
func (c *Client) requestDeviceCode(ctx context.Context, meta Metadata, req LoginRequest) (DeviceAuth, error) {
	form := url.Values{}
	form.Set("client_id", req.ClientID)
	form.Set("scope", withOpenID(req.Scope))

	resp, err := c.postForm(ctx, meta.DeviceAuthorizationEndpoint, form)
	if err != nil {
		return DeviceAuth{}, fmt.Errorf("oidc: device-authorization request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return DeviceAuth{}, fmt.Errorf("oidc: device-authorization endpoint returned %s: %s",
			resp.Status, strings.TrimSpace(string(body)))
	}
	var da DeviceAuth
	if err := json.Unmarshal(body, &da); err != nil {
		return DeviceAuth{}, fmt.Errorf("oidc: decode device-authorization response: %w", err)
	}
	if da.DeviceCode == "" || da.UserCode == "" || da.VerificationURI == "" {
		return DeviceAuth{}, errors.New("oidc: device-authorization response missing required fields")
	}
	return da, nil
}

// poll repeatedly calls the token endpoint until the user approves (success), the grant is denied, or
// the device code expires. It honors RFC 8628 §3.5: `authorization_pending` keeps waiting, `slow_down`
// adds 5s to the interval, anything else is terminal.
func (c *Client) poll(
	ctx context.Context, meta Metadata, clientID string, da DeviceAuth) (tokenResponse, error) {
	interval := da.pollInterval()
	deadline := c.now().Add(time.Duration(da.ExpiresIn) * time.Second)

	for {
		if da.ExpiresIn > 0 && !c.now().Before(deadline) {
			return tokenResponse{}, errors.New("oidc: device code expired before authorization")
		}
		if err := c.sleep(ctx, interval); err != nil {
			return tokenResponse{}, err // context cancelled/timed out
		}

		form := url.Values{}
		form.Set("grant_type", deviceGrantType)
		form.Set("device_code", da.DeviceCode)
		form.Set("client_id", clientID)

		resp, err := c.postForm(ctx, meta.TokenEndpoint, form)
		if err != nil {
			return tokenResponse{}, fmt.Errorf("oidc: token poll: %w", err)
		}
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()

		var tok tokenResponse
		if err := json.Unmarshal(body, &tok); err != nil {
			return tokenResponse{}, fmt.Errorf("oidc: decode token response: %w", err)
		}

		if tok.AccessToken != "" {
			return tok, nil
		}
		switch tok.Error {
		case "authorization_pending":
			// keep polling at the current interval
		case "slow_down":
			interval += 5 * time.Second // RFC 8628 §3.5
		case "":
			return tokenResponse{}, fmt.Errorf("oidc: token endpoint returned %s with no token", resp.Status)
		default:
			return tokenResponse{}, fmt.Errorf("oidc: authorization failed: %s", describeTokenError(tok))
		}
	}
}

// promptUser prints the verification URL and user code so the human can approve the grant. Printed to
// the client's configured writer (the CLI wires this to stderr so stdout stays clean for piping).
func (c *Client) promptUser(da DeviceAuth) {
	fmt.Fprintln(c.out, "To authorize this device, open the following URL in a browser and enter the code:")
	fmt.Fprintf(c.out, "\n    URL:  %s\n    Code: %s\n", da.VerificationURI, da.UserCode)
	if da.VerificationURIComplete != "" {
		fmt.Fprintf(c.out, "\n  Or open this URL directly (code pre-filled):\n    %s\n", da.VerificationURIComplete)
	}
	fmt.Fprintln(c.out, "\nWaiting for authorization...")
}

// postForm POSTs application/x-www-form-urlencoded values to endpoint.
func (c *Client) postForm(ctx context.Context, endpoint string, form url.Values) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	return c.http.Do(req)
}

// withOpenID ensures the OIDC `openid` scope is present (so the IdP issues an OIDC token), preserving
// any caller-supplied scopes.
func withOpenID(scope string) string {
	scope = strings.TrimSpace(scope)
	if scope == "" {
		return "openid"
	}
	for _, s := range strings.Fields(scope) {
		if s == "openid" {
			return scope
		}
	}
	return "openid " + scope
}

// describeTokenError renders an OAuth error response for the user (code + optional description).
func describeTokenError(tok tokenResponse) string {
	if tok.ErrorDescription != "" {
		return tok.Error + ": " + tok.ErrorDescription
	}
	return tok.Error
}

// unverifiedSubject best-effort decodes the `sub` claim from a JWT access token for DISPLAY ONLY (the
// `auth status` output and the stored credential). It performs NO signature/issuer/audience/expiry
// verification — that is exclusively the server's job. Returns "" for a non-JWT or opaque token.
func unverifiedSubject(token string) string {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return ""
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return ""
	}
	var claims struct {
		Sub string `json:"sub"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return ""
	}
	return claims.Sub
}

// sleepCtx sleeps for d unless ctx is cancelled first, in which case it returns ctx.Err().
func sleepCtx(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return ctx.Err()
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}
