package cli

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/oidc"
	"github.com/spf13/cobra"
)

// Auth subcommands (issue #39 PR2): obtain and manage an OIDC identity for the native hook via the
// RFC 8628 device authorization grant. `auth login oidc-device` runs the device flow against a
// configurable IdP and stores the resulting access token under the client data dir; the capture /
// drain path then attaches it as the bearer when no explicit $AGENT_MEMORY_TOKEN is set. The server
// verifies the token (signature / issuer / audience / expiry against the IdP's JWKS) — this client
// only obtains and presents it, attaching the verified subject to the actor/attribution model.

// loginTimeout caps an interactive device-grant login end-to-end, as a safety net above the IdP's own
// device-code expiry (the poll loop also stops when the code expires).
const loginTimeout = 10 * time.Minute

// newOidcClient builds the device-grant client. It is a package var only so a test can inject a mock
// IdP's HTTP client and a no-op poll sleeper; production always gets the real net/http client and
// wall-clock sleeper from oidc.NewClient.
var newOidcClient = func(opts ...oidc.Option) *oidc.Client { return oidc.NewClient(opts...) }

// newAuthCmd is the `agent-memory auth ...` parent command.
func newAuthCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:           "auth",
		Short:         "Manage the client's OIDC identity (login/status/logout) for the native hook",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	cmd.AddCommand(newAuthLoginCmd(), newAuthStatusCmd(), newAuthLogoutCmd())
	return cmd
}

// newAuthLoginCmd is the `auth login ...` parent; the grant type is the subcommand (`oidc-device`), so
// other grants can be added later without changing the surface.
func newAuthLoginCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:           "login",
		Short:         "Obtain an OIDC token for this client",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	cmd.AddCommand(newAuthLoginDeviceCmd())
	return cmd
}

// newAuthLoginDeviceCmd: agent-memory auth login oidc-device --issuer URL --client-id ID [--scope ...]
func newAuthLoginDeviceCmd() *cobra.Command {
	var issuer, clientID, scope, deviceEndpoint, tokenEndpoint, dataDir string
	cmd := &cobra.Command{
		Use:   "oidc-device",
		Short: "Log in via the OIDC device authorization grant (RFC 8628)",
		Long: "Runs the OAuth 2.0 Device Authorization Grant against the given IdP: prints a URL and " +
			"user code to approve in a browser, polls for the token, and stores it under the client " +
			"data dir so the native hook authenticates as the verified OIDC subject. The server " +
			"validates the token against the IdP's JWKS (signature, issuer, audience, expiry).",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{
				"--issuer": issuer, "--client-id": clientID}); err != nil {
				return err
			}
			dir := resolveDataDir(dataDir)

			// Print the user-code instructions to stderr so stdout stays clean (the final confirmation
			// line on stdout is the only "result" a script would read).
			client := newOidcClient(oidc.WithOutput(cmd.ErrOrStderr()))
			ctx, cancel := context.WithTimeout(context.Background(), loginTimeout)
			defer cancel()

			creds, err := client.Login(ctx, oidc.LoginRequest{
				Issuer:             strings.TrimSpace(issuer),
				ClientID:           strings.TrimSpace(clientID),
				Scope:              scope,
				DeviceAuthEndpoint: strings.TrimSpace(deviceEndpoint),
				TokenEndpoint:      strings.TrimSpace(tokenEndpoint),
			})
			if err != nil {
				return err
			}
			if err := oidc.Save(dir, creds); err != nil {
				return err
			}
			who := creds.Subject
			if who == "" {
				who = "(subject not present in token)"
			}
			fmt.Fprintf(cmd.OutOrStdout(), "Logged in as %s via %s; credential stored at %s\n",
				who, creds.Issuer, oidc.CredentialsPath(dir))
			return nil
		},
	}
	cmd.Flags().StringVar(&issuer, "issuer", "", "OIDC issuer URL (e.g. https://idp.example.com)")
	cmd.Flags().StringVar(&clientID, "client-id", "", "OAuth public client id registered for the device flow")
	cmd.Flags().StringVar(&scope, "scope", "", "requested scopes, space-separated (openid is always included)")
	cmd.Flags().StringVar(&deviceEndpoint, "device-endpoint", "",
		"device-authorization endpoint (only if the issuer publishes no discovery document)")
	cmd.Flags().StringVar(&tokenEndpoint, "token-endpoint", "",
		"token endpoint (only if the issuer publishes no discovery document)")
	addDataDirFlag(cmd, &dataDir)
	return cmd
}

// newAuthStatusCmd: agent-memory auth status — show the stored OIDC identity (read-only).
func newAuthStatusCmd() *cobra.Command {
	var dataDir string
	cmd := &cobra.Command{
		Use:           "status",
		Short:         "Show the stored OIDC credential (issuer, subject, expiry)",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			dir := resolveDataDir(dataDir)
			creds, ok, err := oidc.Load(dir)
			if err != nil {
				return err
			}
			out := cmd.OutOrStdout()
			if !ok {
				fmt.Fprintf(out, "Not logged in (no OIDC credential at %s)\n", oidc.CredentialsPath(dir))
				return nil
			}
			subject := creds.Subject
			if subject == "" {
				subject = "(unknown)"
			}
			fmt.Fprintf(out, "Issuer:    %s\n", creds.Issuer)
			fmt.Fprintf(out, "Client ID: %s\n", creds.ClientID)
			fmt.Fprintf(out, "Subject:   %s\n", subject)
			if creds.Scope != "" {
				fmt.Fprintf(out, "Scope:     %s\n", creds.Scope)
			}
			if creds.ExpiresAt.IsZero() {
				fmt.Fprintln(out, "Expires:   (unknown — server enforces the token's own expiry)")
			} else {
				fmt.Fprintf(out, "Expires:   %s\n", creds.ExpiresAt.Format(time.RFC3339))
			}
			if creds.Valid(time.Now()) {
				fmt.Fprintln(out, "Status:    valid")
			} else {
				fmt.Fprintln(out, "Status:    EXPIRED (run `auth login oidc-device` again)")
			}
			return nil
		},
	}
	addDataDirFlag(cmd, &dataDir)
	return cmd
}

// newAuthLogoutCmd: agent-memory auth logout — remove the stored OIDC credential (idempotent).
func newAuthLogoutCmd() *cobra.Command {
	var dataDir string
	cmd := &cobra.Command{
		Use:           "logout",
		Short:         "Remove the stored OIDC credential",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			dir := resolveDataDir(dataDir)
			if err := oidc.Delete(dir); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(), "Logged out (OIDC credential removed).")
			return nil
		},
	}
	addDataDirFlag(cmd, &dataDir)
	return cmd
}

// addDataDirFlag adds the --data-dir flag; an empty value falls back to the env/default data dir.
func addDataDirFlag(cmd *cobra.Command, target *string) {
	cmd.Flags().StringVar(target, "data-dir", "",
		"client data dir holding the credential (default $"+config.EnvDataDir+" or ~/.agent-memory)")
}

// resolveDataDir applies the precedence: explicit --data-dir flag, then the env/default resolution in
// config.Load (which reads $AGENT_MEMORY_DATA_DIR or ~/.agent-memory).
func resolveDataDir(flagValue string) string {
	if v := strings.TrimSpace(flagValue); v != "" {
		return config.ResolveDataDir(v)
	}
	return config.Load().DataDir
}
