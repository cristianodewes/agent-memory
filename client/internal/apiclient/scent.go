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

// Scent is the subset of the server's GET /api/v1/workspaces/{ws}/projects/{p}/scent
// (web.WebDtos.ScentView) the SessionStart orientation block renders (#85): the busiest folders and the
// most-linked hub pages — a compact "what memory exists" map. Unknown fields are ignored.
type Scent struct {
	Folders []ScentFolder `json:"folders"`
	Hubs    []ScentHub    `json:"hubs"`
}

// ScentFolder is one folder/section in the scent map: a top-level path segment and its page count.
type ScentFolder struct {
	Folder string `json:"folder"`
	Pages  int64  `json:"pages"`
}

// ScentHub is one hub page in the scent map: a heavily-referenced page and its inbound-link count.
type ScentHub struct {
	Path    string `json:"path"`
	Title   string `json:"title"`
	Inbound int64  `json:"inbound"`
}

// GetScent fetches the project's scent / orientation map (no LLM) for the SessionStart injection (#85)
// via GET /api/v1/workspaces/{ws}/projects/{p}/scent. folders/hubs (when > 0) cap each list server-side
// (the block is bounded — orientation, not a dump).
//
// Read-only and advisory: returns (scent, nil) on 200; (nil, err) on a transport failure, an unexpected
// status (503 unwired, 404, 5xx), or an undecodable body. The error is advisory — the caller omits the
// scent section and never fails session start.
func (c *Client) GetScent(
	ctx context.Context, workspace, project string, folders, hubs int) (*Scent, error) {
	endpoint := fmt.Sprintf("%s/api/v1/workspaces/%s/projects/%s/scent",
		c.baseURL, url.PathEscape(workspace), url.PathEscape(project))
	q := url.Values{}
	if folders > 0 {
		q.Set("folders", strconv.Itoa(folders))
	}
	if hubs > 0 {
		q.Set("hubs", strconv.Itoa(hubs))
	}
	if len(q) > 0 {
		endpoint += "?" + q.Encode()
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
		return nil, fmt.Errorf("apiclient: GET scent: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("apiclient: GET scent: unexpected status %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, fmt.Errorf("apiclient: read scent body: %w", err)
	}
	var s Scent
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, fmt.Errorf("apiclient: decode scent body: %w", err)
	}
	return &s, nil
}
