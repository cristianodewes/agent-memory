package selfupdate

import "testing"

func TestCompareVersions(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"1.2.0", "1.2.0", 0},
		{"v1.2.0", "1.2.0", 0},        // leading v ignored
		{"1.2.0", "1.3.0", -1},        // minor bump
		{"1.10.0", "1.9.0", 1},        // numeric, not lexical
		{"2.0.0", "1.99.99", 1},       // major dominates
		{"1.2.0", "1.2", 0},           // missing component treated as 0
		{"1.2.1", "1.2", 1},           // patch > implicit 0
		{"1.2.0-rc1", "1.2.0", -1},    // prerelease sorts below release
		{"1.2.0", "1.2.0-rc1", 1},     // and vice versa
		{"1.2.0-rc2", "1.2.0-rc1", 1}, // lexical prerelease compare
		{"1.2.0+build5", "1.2.0", 0},  // build metadata ignored
		{"0.0.0-dev", "1.0.0", -1},    // dev placeholder is older than any release
		{"0.0.0-dev", "0.0.0-dev", 0},
	}
	for _, c := range cases {
		if got := compareVersions(c.a, c.b); got != c.want {
			t.Errorf("compareVersions(%q, %q) = %d, want %d", c.a, c.b, got, c.want)
		}
	}
}
