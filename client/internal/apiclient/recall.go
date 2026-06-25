package apiclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// recallInjectResponse is the wire shape POST /recall/inject returns (issue #21's
// RecallInjectionController): the resolved scope, the number of hits behind the block, and the
// bounded markdown `text` ready to inject. Only `text` is needed by the UserPromptSubmit hook (#84);
// the rest is ignored. An empty `text` is the server saying "nothing cleared the relevance gate" — a
// low-signal prompt — so the hook injects nothing.
type recallInjectResponse struct {
	Text string `json:"text"`
	Hits int    `json:"hits"`
}

// InjectRecall asks the server for the proactive recall block for a user's prompt via
// POST /recall/inject (issue #84, closing the recall-gap: capture is automatic but recall has been
// pull-only). The server runs LLM-assisted recall (query expansion + rerank), curates a bounded
// markdown block, and returns it ready to inject as UserPromptSubmit additional-context.
//
// workspace/project may be empty: blank values are treated as "not provided", so the server resolves
// the most recently active project (DD-003) — the same default the MCP read tools use. (They are sent
// as a pair, mirroring AcceptHandoff; a half-specified scope would be a 400, but identity resolves
// both together.)
//
// Returns:
//   - (text, nil) on HTTP 200 — text may be "" (nothing to inject);
//   - ("", err) on a transport failure, an unexpected status (503 unwired, 400, 5xx), or an
//     unreadable/undecodable body. The error is advisory: the caller (the hook) logs it and proceeds,
//     never blocking or failing the prompt.
func (c *Client) InjectRecall(
	ctx context.Context, workspace, project, prompt string) (string, error) {
	body, err := json.Marshal(map[string]string{
		"prompt": prompt, "workspace": workspace, "project": project,
	})
	if err != nil {
		return "", fmt.Errorf("apiclient: marshal recall inject: %w", err)
	}
	req, err := http.NewRequestWithContext(
		ctx, http.MethodPost, c.baseURL+"/recall/inject", bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("apiclient: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return "", fmt.Errorf("apiclient: POST /recall/inject: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		// 503 (recall unwired), 400 (missing prompt), 5xx, … — advisory; the prompt proceeds with no
		// injection.
		return "", fmt.Errorf(
			"apiclient: POST /recall/inject: unexpected status %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", fmt.Errorf("apiclient: read recall body: %w", err)
	}
	var out recallInjectResponse
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", fmt.Errorf("apiclient: decode recall body: %w", err)
	}
	return out.Text, nil
}
