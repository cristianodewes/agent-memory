package apiclient

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
)

// Briefing is the subset of the server's GET /api/v1/workspaces/{ws}/projects/{p}/briefing
// (web.WebDtos.BriefingView) the SessionStart orientation block renders (#85): lifetime counts, the
// 7d/30d activity windows, the _rules/ and _slots/ listings, and the most recent pages. Unknown fields
// are ignored.
type Briefing struct {
	Scope                  BriefingScope  `json:"scope"`
	Pages                  int64          `json:"pages"`
	Observations           int64          `json:"observations"`
	Sessions               int64          `json:"sessions"`
	Links                  int64          `json:"links"`
	Dependents             int64          `json:"dependents"`
	ObservationsLast7Days  int64          `json:"observationsLast7Days"`
	ObservationsLast30Days int64          `json:"observationsLast30Days"`
	Rules                  []string       `json:"rules"`
	Slots                  []string       `json:"slots"`
	Recent                 []BriefingPage `json:"recent"`
}

// BriefingScope echoes the resolved scope the briefing answered for.
type BriefingScope struct {
	Workspace string `json:"workspace"`
	Project   string `json:"project"`
}

// BriefingPage is one recent page (metadata only) in a briefing. Only the fields the orientation block
// renders are modeled.
type BriefingPage struct {
	Path      string `json:"path"`
	Title     string `json:"title"`
	UpdatedAt string `json:"updatedAt"`
}

// GetBriefing fetches the structured project briefing (no LLM) for the SessionStart orientation block
// (#85) via GET /api/v1/workspaces/{ws}/projects/{p}/briefing. recentLimit (when > 0) caps the recent
// pages the server returns (the orientation block is bounded — orientation, not a dump).
//
// Read-only and advisory: the call is sent unauthenticated unless a token is configured. Returns
// (briefing, nil) on 200; (nil, err) on a transport failure, an unexpected status (503 unwired, 404,
// 5xx), or an undecodable body. The error is advisory — the caller omits the briefing section and
// never fails session start.
func (c *Client) GetBriefing(
	ctx context.Context, workspace, project string, recentLimit int) (*Briefing, error) {
	endpoint := fmt.Sprintf("%s/api/v1/workspaces/%s/projects/%s/briefing",
		c.baseURL, url.PathEscape(workspace), url.PathEscape(project))
	if recentLimit > 0 {
		endpoint += "?limit=" + strconv.Itoa(recentLimit)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("apiclient: build request: %w", err)
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("apiclient: GET briefing: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("apiclient: GET briefing: unexpected status %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("apiclient: read briefing body: %w", err)
	}
	var b Briefing
	if err := json.Unmarshal(raw, &b); err != nil {
		return nil, fmt.Errorf("apiclient: decode briefing body: %w", err)
	}
	return &b, nil
}
