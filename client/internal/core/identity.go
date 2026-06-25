package core

import (
	"encoding/json"
	"fmt"
	"strings"
)

// WorkspaceId is the top coordinate of the 3-tuple identity (workspace, project, path). It is a
// normalized slug (trimmed, lower-cased ASCII, single segment). Mirrors
// com.agentmemory.core.WorkspaceId; serializes as a bare JSON string.
type WorkspaceId struct{ value string }

// ProjectId is the middle coordinate. Same normal form as WorkspaceId. Mirrors
// com.agentmemory.core.ProjectId; serializes as a bare JSON string.
type ProjectId struct{ value string }

// PagePath is the innermost coordinate: a project-root-relative wiki page path in canonical form
// (see normalizePagePath). Mirrors com.agentmemory.core.PagePath; serializes as a bare JSON string.
type PagePath struct{ value string }

// NewWorkspaceID normalizes raw into a WorkspaceId, or returns an error on blank/path-like input.
func NewWorkspaceID(raw string) (WorkspaceId, error) {
	v, err := normalizeSlug(raw, "workspace")
	return WorkspaceId{v}, err
}

// NewProjectID normalizes raw into a ProjectId, or returns an error on blank/path-like input.
func NewProjectID(raw string) (ProjectId, error) {
	v, err := normalizeSlug(raw, "project")
	return ProjectId{v}, err
}

// NewPagePath normalizes raw into a PagePath, or returns an error on traversal/empty/NUL input.
func NewPagePath(raw string) (PagePath, error) {
	v, err := normalizePagePath(raw)
	return PagePath{v}, err
}

// String returns the normalized value.
func (w WorkspaceId) String() string { return w.value }
func (p ProjectId) String() string   { return p.value }
func (p PagePath) String() string    { return p.value }

// FileName returns the final path segment (e.g. "recall.md" for "concepts/recall.md").
func (p PagePath) FileName() string {
	if i := strings.LastIndexByte(p.value, '/'); i >= 0 {
		return p.value[i+1:]
	}
	return p.value
}

// TopFolder returns the first folder of the path, or "" when the page sits at the project root.
func (p PagePath) TopFolder() string {
	if i := strings.IndexByte(p.value, '/'); i >= 0 {
		return p.value[:i]
	}
	return ""
}

// --- JSON: bare normalized string on the wire -----------------------------------------------------

func (w WorkspaceId) MarshalJSON() ([]byte, error) { return json.Marshal(w.value) }
func (p ProjectId) MarshalJSON() ([]byte, error)   { return json.Marshal(p.value) }
func (p PagePath) MarshalJSON() ([]byte, error)    { return json.Marshal(p.value) }

func (w *WorkspaceId) UnmarshalJSON(b []byte) error {
	s, err := unmarshalString(b, "workspace")
	if err != nil {
		return err
	}
	v, err := normalizeSlug(s, "workspace")
	if err != nil {
		return err
	}
	w.value = v
	return nil
}

func (p *ProjectId) UnmarshalJSON(b []byte) error {
	s, err := unmarshalString(b, "project")
	if err != nil {
		return err
	}
	v, err := normalizeSlug(s, "project")
	if err != nil {
		return err
	}
	p.value = v
	return nil
}

func (p *PagePath) UnmarshalJSON(b []byte) error {
	s, err := unmarshalString(b, "path")
	if err != nil {
		return err
	}
	v, err := normalizePagePath(s)
	if err != nil {
		return err
	}
	p.value = v
	return nil
}

func unmarshalString(b []byte, label string) (string, error) {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return "", fmt.Errorf("core: %s must be a JSON string: %w", label, err)
	}
	return s, nil
}

// Identity is the typed 3-tuple (workspace, project, path) carried by every domain record
// (ARCHITECTURE invariant #4). Page-scoped rows set Path; project-scoped rows leave it nil. Mirrors
// com.agentmemory.core.Identity: serializes as a nested object {workspace, project, path?} with
// path omitted when nil.
type Identity struct {
	Workspace WorkspaceId `json:"workspace"`
	Project   ProjectId   `json:"project"`
	// Path is a pointer so a project-scoped identity (nil) omits the field entirely, matching the
	// server's @JsonInclude(NON_NULL); a page-scoped identity carries a non-nil PagePath.
	Path *PagePath `json:"path,omitempty"`
}

// ProjectIdentity builds a project-scoped Identity (no page coordinate).
func ProjectIdentity(ws WorkspaceId, p ProjectId) Identity {
	return Identity{Workspace: ws, Project: p}
}

// PageIdentity builds a page-scoped Identity.
func PageIdentity(ws WorkspaceId, p ProjectId, path PagePath) Identity {
	return Identity{Workspace: ws, Project: p, Path: &path}
}

// IsPageScoped reports whether this identity names a specific page (Path is set).
func (i Identity) IsPageScoped() bool { return i.Path != nil }
