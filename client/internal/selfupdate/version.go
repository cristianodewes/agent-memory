package selfupdate

import (
	"strconv"
	"strings"
)

// compareVersions compares two semver-ish strings, returning -1 if a < b, 0 if a == b
// and +1 if a > b. A leading "v" and any "+build" metadata are ignored. Dotted numeric
// components are compared numerically; a version carrying a "-prerelease" tail sorts
// BELOW the same version without one (1.2.0-rc1 < 1.2.0), per SemVer §11. The function
// is total — it never panics — so the dev placeholder "0.0.0-dev" compares cleanly
// (and, having a 0.0.0 core, always sorts below any real release).
func compareVersions(a, b string) int {
	aCore, aPre := splitVersion(a)
	bCore, bPre := splitVersion(b)
	if c := compareNumeric(aCore, bCore); c != 0 {
		return c
	}
	return comparePrerelease(aPre, bPre)
}

// splitVersion normalizes a version into its dotted numeric core and prerelease tail:
// "v1.2.0-rc1+meta" → ("1.2.0", "rc1").
func splitVersion(v string) (core, prerelease string) {
	v = strings.TrimSpace(v)
	v = strings.TrimPrefix(v, "v")
	if i := strings.IndexByte(v, '+'); i >= 0 { // drop build metadata
		v = v[:i]
	}
	if i := strings.IndexByte(v, '-'); i >= 0 {
		return v[:i], v[i+1:]
	}
	return v, ""
}

func compareNumeric(a, b string) int {
	aParts := strings.Split(a, ".")
	bParts := strings.Split(b, ".")
	n := len(aParts)
	if len(bParts) > n {
		n = len(bParts)
	}
	for i := 0; i < n; i++ {
		av := numericAt(aParts, i)
		bv := numericAt(bParts, i)
		switch {
		case av < bv:
			return -1
		case av > bv:
			return 1
		}
	}
	return 0
}

func numericAt(parts []string, i int) int {
	if i >= len(parts) {
		return 0
	}
	n, err := strconv.Atoi(strings.TrimSpace(parts[i]))
	if err != nil {
		return 0
	}
	return n
}

// comparePrerelease applies the SemVer rule that a present prerelease tail sorts below an
// absent one; two present tails are compared lexically (enough for rc/edge tags).
func comparePrerelease(a, b string) int {
	switch {
	case a == "" && b == "":
		return 0
	case a == "": // a is the final release, b is a prerelease → a > b
		return 1
	case b == "":
		return -1
	}
	return strings.Compare(a, b)
}
