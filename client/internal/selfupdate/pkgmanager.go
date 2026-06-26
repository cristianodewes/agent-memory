package selfupdate

import "strings"

// PackageManager identifies an OS package manager that appears to own the installed
// binary, together with the command the user should run to upgrade through it.
type PackageManager struct {
	Name    string // human name, e.g. "Homebrew"
	Command string // suggested upgrade command
}

// DetectPackageManager inspects the running binary's path and returns the package
// manager that appears to own it (winget, Scoop or Homebrew), or ok=false when the
// binary looks self-managed (a manual download / goreleaser archive). When a manager is
// detected the caller must NOT overwrite the binary — it should instruct the user to
// upgrade through that manager so the installation stays consistent.
//
// The heuristic is path-based and matches the managers' well-known install roots
// case-insensitively, normalizing separators so it is testable on any host OS.
func DetectPackageManager(execPath string) (PackageManager, bool) {
	p := strings.ToLower(strings.ReplaceAll(execPath, "\\", "/"))
	switch {
	case strings.Contains(p, "/scoop/"):
		return PackageManager{Name: "Scoop", Command: "scoop update agent-memory"}, true
	case strings.Contains(p, "/winget/") ||
		strings.Contains(p, "/microsoft/winget/") ||
		strings.Contains(p, "/windowsapps/"):
		return PackageManager{Name: "winget", Command: "winget upgrade agent-memory"}, true
	case isHomebrewPath(p):
		return PackageManager{Name: "Homebrew", Command: "brew upgrade agent-memory"}, true
	}
	return PackageManager{}, false
}

// isHomebrewPath matches the Cellar/keg layout Homebrew installs into on both macOS
// (/usr/local, /opt/homebrew) and Linux (~/.linuxbrew, /home/linuxbrew).
func isHomebrewPath(lowerSlashPath string) bool {
	return strings.Contains(lowerSlashPath, "/cellar/") ||
		strings.Contains(lowerSlashPath, "/homebrew/") ||
		strings.Contains(lowerSlashPath, "/.linuxbrew/") ||
		strings.Contains(lowerSlashPath, "/linuxbrew/")
}
