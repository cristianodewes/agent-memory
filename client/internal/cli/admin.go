package cli

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"

	"github.com/cristianodewes/agent-memory/client/internal/config"
	"github.com/cristianodewes/agent-memory/client/internal/identity"
	"github.com/cristianodewes/agent-memory/client/internal/oidc"
	"github.com/spf13/cobra"
)

// The admin commands (reindex, the project lifecycle, reset, time travel, backup/restore, bootstrap
// and user management) are thin HTTP triggers of the server's operational routes; the client never
// touches Postgres or the wiki itself (ARCHITECTURE §2.1). This file decides, in one place, which
// server they call and which bearer token they send, so they go exactly where the hooks and the drain
// go and authenticate the same way.

// legacyEnvServerURL is the variable the admin commands used to read instead of the client's
// AGENT_MEMORY_SERVER_URL. It is still honored, below AGENT_MEMORY_SERVER_URL, so a setup that only
// sets it keeps working.
const legacyEnvServerURL = "AGENT_MEMORY_SERVER"

// adminTarget is the server an admin command calls and the bearer token it attaches ("" sends none).
type adminTarget struct {
	baseURL string
	token   string
}

// resolveAdminTarget resolves an admin command's server and token. The --server and --token flags win;
// otherwise it is the hooks' resolution: the nearest .agent-memory.toml marker's server_url and token,
// then AGENT_MEMORY_SERVER_URL and AGENT_MEMORY_TOKEN, then the default server, with the OIDC device
// login as the token of last resort. The old AGENT_MEMORY_SERVER is read only when
// AGENT_MEMORY_SERVER_URL is unset.
func resolveAdminTarget(serverFlag, tokenFlag string) adminTarget {
	cfg := config.Load()
	if strings.TrimSpace(os.Getenv(config.EnvServerURL)) == "" {
		if legacy := strings.TrimSpace(os.Getenv(legacyEnvServerURL)); legacy != "" {
			cfg.ServerURL = legacy
		}
	}
	cwd, _ := os.Getwd()
	id := identity.Resolve(cwd)
	cfg = cfg.WithIdentityOverrides(id.ServerURL, id.Token)
	if s := strings.TrimSpace(serverFlag); s != "" {
		cfg.ServerURL = s
	}
	if t := strings.TrimSpace(tokenFlag); t != "" {
		cfg.Token = t
	}
	if cfg.Token == "" {
		cfg.Token = oidc.AccessToken(cfg.DataDir)
	}
	return adminTarget{baseURL: strings.TrimRight(cfg.ServerURL, "/"), token: cfg.Token}
}

// newRequest builds a request to <baseURL><path> with the bearer token and, when there is a body, its
// JSON Content-Type.
func (t adminTarget) newRequest(method, path string, body []byte) (*http.Request, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, t.baseURL+path, reader)
	if err != nil {
		return nil, fmt.Errorf("building request: %w", err)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if t.token != "" {
		req.Header.Set("Authorization", "Bearer "+t.token)
	}
	return req, nil
}

// addAdminFlags adds the --server and --token flags every admin command takes.
func addAdminFlags(cmd *cobra.Command, server, token *string) {
	cmd.Flags().StringVar(server, "server", "",
		"server base URL (default: the .agent-memory.toml marker, $AGENT_MEMORY_SERVER_URL or "+
			config.DefaultServerURL+")")
	cmd.Flags().StringVar(token, "token", "",
		"root bearer token for the admin routes (default: the .agent-memory.toml marker, "+
			"$AGENT_MEMORY_TOKEN or the OIDC login)")
}
