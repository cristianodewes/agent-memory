package apiclient

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// Handoff is the wire shape the server's POST /handoff/accept returns (issue #22's HandoffController
// flat body, NOT the nested core.Handoff). Only the fields the SessionStart injection (#23) renders
// are modeled; unknown fields are ignored.
type Handoff struct {
	ID            string   `json:"id"`
	Workspace     string   `json:"workspace"`
	Project       string   `json:"project"`
	FromSession   string   `json:"fromSession"`
	Status        string   `json:"status"`
	Summary       string   `json:"summary"`
	OpenQuestions []string `json:"openQuestions"`
	NextSteps     []string `json:"nextSteps"`
	CreatedAt     string   `json:"createdAt"`
	AcceptedAt    *string  `json:"acceptedAt,omitempty"`
}

// AcceptHandoff consumes the latest open handoff for (workspace, project) via POST /handoff/accept.
// This single call both FETCHES and ACCEPTS (single-use): on success the server returns the handoff
// and marks it accepted, so it is not re-injected on the next session start (#23 acceptance: consumed
// after accept).
//
// Returns:
//   - (handoff, nil) when a handoff was accepted (HTTP 200 with a body);
//   - (nil, nil) when there is no open handoff (HTTP 204) — a clean no-op, not an error;
//   - (nil, err) on a transport failure, an unexpected status, or an unreadable body. The error is
//     advisory: the caller (the hook) logs it but must not fail session start.
func (c *Client) AcceptHandoff(ctx context.Context, workspace, project string) (*Handoff, error) {
	body, err := json.Marshal(map[string]string{"workspace": workspace, "project": project})
	if err != nil {
		return nil, fmt.Errorf("apiclient: marshal handoff accept: %w", err)
	}
	req, err := http.NewRequestWithContext(
		ctx, http.MethodPost, c.baseURL+"/handoff/accept", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("apiclient: build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("apiclient: POST /handoff/accept: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	switch {
	case resp.StatusCode == http.StatusNoContent:
		return nil, nil // no open handoff: clean no-op
	case resp.StatusCode >= 200 && resp.StatusCode < 300:
		raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		if err != nil {
			return nil, fmt.Errorf("apiclient: read handoff body: %w", err)
		}
		var h Handoff
		if err := json.Unmarshal(raw, &h); err != nil {
			return nil, fmt.Errorf("apiclient: decode handoff body: %w", err)
		}
		return &h, nil
	default:
		// 503 (unwired), 400, 5xx, … — advisory; session start proceeds without injection.
		return nil, fmt.Errorf("apiclient: POST /handoff/accept: unexpected status %d", resp.StatusCode)
	}
}
