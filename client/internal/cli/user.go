package cli

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/spf13/cobra"
)

// User-management subcommands (issue #39): a thin HTTP front-end over the server's
// /users/* admin endpoints for a shared/multi-user server. Each user gets its own
// bearer token resolving to an identity recorded in the audit log. These are ADMIN
// operations, so they send the ROOT token (--token or $AGENT_MEMORY_TOKEN); the
// server rejects a per-user token here.
//
// A freshly issued token is printed once by `add` and `rotate-token` and never again
// (the server stores only its hash).

// newUserCmd is the `agent-memory user ...` parent command.
func newUserCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:           "user",
		Short:         "Manage per-user tokens on a shared server (add/list/expire/revive/rotate-token)",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	cmd.AddCommand(
		newUserAddCmd(),
		newUserListCmd(),
		newUserExpireCmd(),
		newUserReviveCmd(),
		newUserRotateTokenCmd(),
	)
	return cmd
}

// newUserAddCmd: agent-memory user add --username U
func newUserAddCmd() *cobra.Command {
	var serverURL, token, username string
	cmd := &cobra.Command{
		Use:           "add",
		Short:         "Create a user and issue its first token (printed once)",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{"--username": username}); err != nil {
				return err
			}
			body := map[string]any{"username": username}
			return postUser(cmd.OutOrStdout(), http.MethodPost,
				resolveServerURL(serverURL), "/users/add", resolveToken(token), body)
		},
	}
	addServerFlag(cmd, &serverURL)
	addTokenFlag(cmd, &token)
	cmd.Flags().StringVar(&username, "username", "", "the new user's name (the audit-log actor)")
	return cmd
}

// newUserListCmd: agent-memory user list
func newUserListCmd() *cobra.Command {
	var serverURL, token string
	cmd := &cobra.Command{
		Use:           "list",
		Short:         "List users and their status",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			return postUser(cmd.OutOrStdout(), http.MethodGet,
				resolveServerURL(serverURL), "/users/list", resolveToken(token), nil)
		},
	}
	addServerFlag(cmd, &serverURL)
	addTokenFlag(cmd, &token)
	return cmd
}

// newUserExpireCmd: agent-memory user expire --username U
func newUserExpireCmd() *cobra.Command {
	return newUserStatusCmd("expire", "Revoke a user's token (status -> expired)", "/users/expire")
}

// newUserReviveCmd: agent-memory user revive --username U
func newUserReviveCmd() *cobra.Command {
	return newUserStatusCmd("revive", "Re-activate an expired user (status -> active)", "/users/revive")
}

// newUserStatusCmd builds the expire/revive commands (same shape: a username -> a status flip).
func newUserStatusCmd(use, short, path string) *cobra.Command {
	var serverURL, token, username string
	cmd := &cobra.Command{
		Use:           use,
		Short:         short,
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{"--username": username}); err != nil {
				return err
			}
			body := map[string]any{"username": username}
			return postUser(cmd.OutOrStdout(), http.MethodPost,
				resolveServerURL(serverURL), path, resolveToken(token), body)
		},
	}
	addServerFlag(cmd, &serverURL)
	addTokenFlag(cmd, &token)
	cmd.Flags().StringVar(&username, "username", "", "the user to "+use)
	return cmd
}

// newUserRotateTokenCmd: agent-memory user rotate-token --username U
func newUserRotateTokenCmd() *cobra.Command {
	var serverURL, token, username string
	cmd := &cobra.Command{
		Use:           "rotate-token",
		Short:         "Issue a fresh token for a user, invalidating the old one (printed once)",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if err := requireAll(map[string]string{"--username": username}); err != nil {
				return err
			}
			body := map[string]any{"username": username}
			return postUser(cmd.OutOrStdout(), http.MethodPost,
				resolveServerURL(serverURL), "/users/rotate-token", resolveToken(token), body)
		},
	}
	addServerFlag(cmd, &serverURL)
	addTokenFlag(cmd, &token)
	cmd.Flags().StringVar(&username, "username", "", "the user whose token to rotate")
	return cmd
}

// --- shared helpers -------------------------------------------------------------------------------

// addTokenFlag adds the --token flag used to authenticate admin requests as root.
func addTokenFlag(cmd *cobra.Command, target *string) {
	cmd.Flags().StringVar(target, "token", "",
		"root bearer token for admin auth (default $"+"AGENT_MEMORY_TOKEN)")
}

// resolveToken applies the precedence: explicit flag, then $AGENT_MEMORY_TOKEN.
func resolveToken(flagValue string) string {
	if strings.TrimSpace(flagValue) != "" {
		return strings.TrimSpace(flagValue)
	}
	return strings.TrimSpace(os.Getenv("AGENT_MEMORY_TOKEN"))
}

// postUser sends a user-management request to <base><path> with the bearer token and prints the
// server's JSON response. A nil body sends no payload (GET). A non-2xx status surfaces the server's
// reason (e.g. a 403 when not using the root token, or a 409 on a duplicate user).
func postUser(out io.Writer, method, base, path, token string, body map[string]any) error {
	var reader io.Reader
	if body != nil {
		payload, err := json.Marshal(body)
		if err != nil {
			return fmt.Errorf("encoding request: %w", err)
		}
		reader = bytes.NewReader(payload)
	}
	url := base + path
	req, err := http.NewRequest(method, url, reader)
	if err != nil {
		return fmt.Errorf("building request: %w", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	client := &http.Client{Timeout: 1 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("calling %s: %w", url, err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("reading response: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("%s failed: server returned %s: %s",
			strings.TrimPrefix(path, "/"), resp.Status, strings.TrimSpace(string(respBody)))
	}
	var pretty bytes.Buffer
	if json.Indent(&pretty, respBody, "", "  ") == nil {
		fmt.Fprintln(out, pretty.String())
	} else {
		fmt.Fprintln(out, strings.TrimSpace(string(respBody)))
	}
	return nil
}
