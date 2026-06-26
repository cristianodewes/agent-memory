package openaioauth

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// CodexClientID is the public Codex/OpenCode OAuth client id used for ChatGPT sign-in (the same id
// the Codex CLI and the ai-memory reference use). The subscription OAuth client id is unofficial.
const CodexClientID = "app_EMoamEEZ73f0CkXaXp7hrann"

// DefaultBaseURL is the OpenAI auth host. All login endpoints hang off it; tests point it at a stub.
const DefaultBaseURL = "https://auth.openai.com"

// httpTimeout bounds each individual auth request.
const httpTimeout = 60 * time.Second

// defaultPollInterval is the fallback poll cadence when the usercode response omits `interval`.
const defaultPollInterval = 5 * time.Second

// Client runs the OpenAI device-authorization + authorization-code login. The HTTP client, sleeper,
// clock and base URL are injectable so tests run against a stub with no real network and no real wait.
type Client struct {
	http     *http.Client
	sleep    func(context.Context, time.Duration) error
	now      func() time.Time
	out      io.Writer
	baseURL  string
	clientID string
}

// Option configures a Client.
type Option func(*Client)

// WithHTTPClient overrides the HTTP client (tests point it at an httptest server).
func WithHTTPClient(h *http.Client) Option { return func(c *Client) { c.http = h } }

// WithSleeper overrides the poll sleep (tests make it a no-op so polling does not actually wait).
func WithSleeper(s func(context.Context, time.Duration) error) Option {
	return func(c *Client) { c.sleep = s }
}

// WithClock overrides the wall clock (tests freeze time so token expiry is deterministic).
func WithClock(now func() time.Time) Option { return func(c *Client) { c.now = now } }

// WithOutput overrides where the user-code instructions are printed (default the CLI's stderr).
func WithOutput(w io.Writer) Option { return func(c *Client) { c.out = w } }

// WithBaseURL overrides the OpenAI auth host (tests point it at a stub serving the device endpoints).
func WithBaseURL(u string) Option { return func(c *Client) { c.baseURL = strings.TrimRight(u, "/") } }

// NewClient builds a login client with production defaults.
func NewClient(opts ...Option) *Client {
	c := &Client{
		http:     &http.Client{Timeout: httpTimeout},
		sleep:    sleepCtx,
		now:      time.Now,
		out:      io.Discard,
		baseURL:  DefaultBaseURL,
		clientID: CodexClientID,
	}
	for _, o := range opts {
		o(c)
	}
	return c
}

// Login runs the full flow: request a user code → print the browser URL + code → poll until the user
// approves (the device-auth endpoint returns the authorization code + PKCE verifier) → exchange them
// for tokens → return a persist-ready Token (with the ChatGPT account id decoded from the id token).
// The caller still needs to Save() it.
func (c *Client) Login(ctx context.Context, timeout time.Duration) (Token, error) {
	device, err := c.requestUserCode(ctx)
	if err != nil {
		return Token{}, err
	}
	fmt.Fprintf(c.out, "To authorize ChatGPT access, open this URL in a browser:\n\n    %s/codex/device\n",
		c.baseURL)
	fmt.Fprintf(c.out, "\nand enter the code:  %s\n\nWaiting for authorization...\n", device.UserCode)

	code, err := c.poll(ctx, device, timeout)
	if err != nil {
		return Token{}, err
	}
	tokens, err := c.exchangeCode(ctx, code)
	if err != nil {
		return Token{}, err
	}
	refresh := strings.TrimSpace(tokens.RefreshToken)
	if refresh == "" {
		return Token{}, fmt.Errorf("openai-oauth: token response did not include a refresh_token")
	}
	expiresIn := tokens.ExpiresIn
	if expiresIn <= 0 {
		expiresIn = 3600
	}
	accountID := accountIDFromJWT(tokens.IDToken)
	if accountID == "" {
		accountID = accountIDFromJWT(tokens.AccessToken)
	}
	return Token{
		Access:    tokens.AccessToken,
		Refresh:   refresh,
		Expires:   c.now().UnixMilli() + expiresIn*1000,
		AccountID: accountID,
	}, nil
}

// userCode is the device-authorization response: the id+code pair plus the browser code to show.
type userCode struct {
	DeviceAuthID string `json:"device_auth_id"`
	UserCode     string `json:"user_code"`
	Interval     string `json:"interval"`
}

func (c *Client) requestUserCode(ctx context.Context) (userCode, error) {
	body, _ := json.Marshal(map[string]string{"client_id": c.clientID})
	resp, err := c.postJSON(ctx, c.baseURL+"/api/accounts/deviceauth/usercode", body)
	if err != nil {
		return userCode{}, fmt.Errorf("openai-oauth: device authorization request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return userCode{}, statusError("device authorization", resp)
	}
	var uc userCode
	if err := json.NewDecoder(resp.Body).Decode(&uc); err != nil {
		return userCode{}, fmt.Errorf("openai-oauth: decode device authorization: %w", err)
	}
	if uc.DeviceAuthID == "" || uc.UserCode == "" {
		return userCode{}, fmt.Errorf("openai-oauth: device authorization response missing required fields")
	}
	return uc, nil
}

// authCode is the device-token poll's success payload: the authorization code and its PKCE verifier
// (OpenAI's device flow manages the PKCE pair server-side and returns the verifier to relay).
type authCode struct {
	AuthorizationCode string `json:"authorization_code"`
	CodeVerifier      string `json:"code_verifier"`
}

func (c *Client) poll(ctx context.Context, device userCode, timeout time.Duration) (authCode, error) {
	deadline := c.now().Add(timeout)
	interval := pollInterval(device.Interval)
	for {
		if !c.now().Before(deadline) {
			return authCode{}, fmt.Errorf("openai-oauth: timed out waiting for authorization")
		}
		if err := c.sleep(ctx, interval); err != nil {
			return authCode{}, err // context cancelled / timed out
		}

		body, _ := json.Marshal(map[string]string{
			"device_auth_id": device.DeviceAuthID,
			"user_code":      device.UserCode,
		})
		resp, err := c.postJSON(ctx, c.baseURL+"/api/accounts/deviceauth/token", body)
		if err != nil {
			return authCode{}, fmt.Errorf("openai-oauth: authorization poll: %w", err)
		}
		status := resp.StatusCode
		if status == http.StatusOK {
			var code authCode
			err := json.NewDecoder(resp.Body).Decode(&code)
			_ = resp.Body.Close()
			if err != nil {
				return authCode{}, fmt.Errorf("openai-oauth: decode authorization code: %w", err)
			}
			return code, nil
		}
		// 403 / 404 mean "still pending" — keep polling; anything else is terminal.
		if status != http.StatusForbidden && status != http.StatusNotFound {
			err := statusError("authorization poll", resp)
			_ = resp.Body.Close()
			return authCode{}, err
		}
		_ = resp.Body.Close()
	}
}

// tokenResponse is the OAuth token endpoint's reply to the authorization_code (and later refresh_token)
// grant.
type tokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	IDToken      string `json:"id_token"`
	ExpiresIn    int64  `json:"expires_in"`
}

func (c *Client) exchangeCode(ctx context.Context, code authCode) (tokenResponse, error) {
	form := url.Values{}
	form.Set("grant_type", "authorization_code")
	form.Set("code", code.AuthorizationCode)
	form.Set("redirect_uri", c.baseURL+"/deviceauth/callback")
	form.Set("client_id", c.clientID)
	form.Set("code_verifier", code.CodeVerifier)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/oauth/token",
		strings.NewReader(form.Encode()))
	if err != nil {
		return tokenResponse{}, fmt.Errorf("openai-oauth: build token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return tokenResponse{}, fmt.Errorf("openai-oauth: token exchange: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return tokenResponse{}, statusError("token exchange", resp)
	}
	var tok tokenResponse
	if err := json.NewDecoder(resp.Body).Decode(&tok); err != nil {
		return tokenResponse{}, fmt.Errorf("openai-oauth: decode token response: %w", err)
	}
	if tok.AccessToken == "" {
		return tokenResponse{}, fmt.Errorf("openai-oauth: token response did not include an access_token")
	}
	return tok, nil
}

func (c *Client) postJSON(ctx context.Context, endpoint string, body []byte) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(string(body)))
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	return c.http.Do(req)
}

func statusError(label string, resp *http.Response) error {
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
	return fmt.Errorf("openai-oauth: %s failed (%s): %s", label, resp.Status, strings.TrimSpace(string(body)))
}

func pollInterval(raw string) time.Duration {
	if secs, err := strconv.Atoi(strings.TrimSpace(raw)); err == nil && secs > 0 {
		return time.Duration(secs) * time.Second
	}
	return defaultPollInterval
}

// accountIDFromJWT best-effort decodes the ChatGPT account id from a JWT's claims (no signature
// verification — display/header use only): `chatgpt_account_id`, the namespaced
// `https://api.openai.com/auth.chatgpt_account_id`, or `organizations[0].id`. Returns "" if absent.
func accountIDFromJWT(token string) string {
	parts := strings.Split(token, ".")
	if len(parts) < 2 {
		return ""
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return ""
	}
	var claims struct {
		AccountID string `json:"chatgpt_account_id"`
		Auth      struct {
			AccountID string `json:"chatgpt_account_id"`
		} `json:"https://api.openai.com/auth"`
		Organizations []struct {
			ID string `json:"id"`
		} `json:"organizations"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil {
		return ""
	}
	switch {
	case claims.AccountID != "":
		return claims.AccountID
	case claims.Auth.AccountID != "":
		return claims.Auth.AccountID
	case len(claims.Organizations) > 0 && claims.Organizations[0].ID != "":
		return claims.Organizations[0].ID
	default:
		return ""
	}
}

// sleepCtx sleeps for d unless ctx is cancelled first.
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
