package core

import (
	"fmt"
	"strings"
)

// normalizePagePath is the Go twin of com.agentmemory.core.PathNormalizer. The two MUST agree
// exactly: a PagePath is part of the 3-tuple identity, so the same spelling has to collapse to the
// same key on both sides or a page would index twice. The rule set (kept in lockstep with the Java
// doc comment and docs/contracts/serialization.md) is:
//
//  1. backslashes -> forward slashes
//  2. collapse runs of slashes
//  3. strip a leading "/" or "./" (paths are project-root-relative)
//  4. trim each segment; drop empty and "." segments
//  5. reject ".." segments (no traversal out of the project root)
//  6. lower-case ASCII (case-insensitive identity); non-ASCII left as-is
//  7. ensure exactly one ".md" suffix (case-insensitive)
//
// It returns an error (never panics) so callers can surface bad input; the PagePath constructor
// wraps it.
func normalizePagePath(raw string) (string, error) {
	if strings.IndexByte(raw, 0) >= 0 {
		return "", fmt.Errorf("core: page path must not contain NUL")
	}

	unified := strings.ReplaceAll(raw, "\\", "/")
	var out strings.Builder
	for _, segment := range strings.Split(unified, "/") {
		trimmed := strings.TrimSpace(segment)
		if trimmed == "" || trimmed == "." {
			continue // drops leading "/", "./", "//", stray whitespace-only segments
		}
		if trimmed == ".." {
			return "", fmt.Errorf("core: page path must not contain '..': %q", raw)
		}
		if out.Len() > 0 {
			out.WriteByte('/')
		}
		out.WriteString(asciiLower(trimmed))
	}

	if out.Len() == 0 {
		return "", fmt.Errorf("core: page path is empty after normalization: %q", raw)
	}

	normalized := out.String()
	if !hasMDSuffixFold(normalized) {
		normalized += ".md"
	}
	return normalized, nil
}

// asciiLower lower-cases ASCII A–Z only, leaving every other rune untouched. This matches Java's
// toLowerCase(Locale.ROOT) on the ASCII range without attempting Unicode case folding (which the
// two languages do not implement identically).
func asciiLower(s string) string {
	var b []byte
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' {
			if b == nil {
				b = []byte(s)
			}
			b[i] = c + ('a' - 'A')
		}
	}
	if b == nil {
		return s
	}
	return string(b)
}

// hasMDSuffixFold reports whether s already ends in ".md" case-insensitively.
func hasMDSuffixFold(s string) bool {
	if len(s) < 3 {
		return false
	}
	return strings.EqualFold(s[len(s)-3:], ".md")
}

// normalizeSlug is the Go twin of WorkspaceId.normalizeSlug (com.agentmemory.core): trim, reject blanks and path-like
// values (a slug is one directory name), lower-case ASCII for case-insensitive identity.
func normalizeSlug(raw, label string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", fmt.Errorf("core: %s must not be blank", label)
	}
	if strings.ContainsAny(trimmed, "/\\\x00") {
		return "", fmt.Errorf("core: %s must be a single segment (no '/', '\\' or NUL): %q", label, raw)
	}
	return asciiLower(trimmed), nil
}
