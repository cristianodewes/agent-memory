package selfupdate

import "testing"

func TestDetectPackageManager(t *testing.T) {
	cases := []struct {
		name     string
		path     string
		wantName string // "" = expect ok=false (self-managed)
	}{
		{"scoop", `C:\Users\dewes\scoop\apps\agent-memory\current\agent-memory.exe`, "Scoop"},
		{"winget packages", `C:\Users\dewes\AppData\Local\Microsoft\WinGet\Packages\x\agent-memory.exe`, "winget"},
		{"windows apps", `C:\Program Files\WindowsApps\agent-memory\agent-memory.exe`, "winget"},
		{"homebrew cellar macos", "/usr/local/Cellar/agent-memory/1.2.0/bin/agent-memory", "Homebrew"},
		{"homebrew opt arm", "/opt/homebrew/bin/agent-memory", "Homebrew"},
		{"linuxbrew", "/home/linuxbrew/.linuxbrew/Cellar/agent-memory/1.2.0/bin/agent-memory", "Homebrew"},
		{"manual unix", "/usr/local/bin/agent-memory", ""},
		{"manual home", "/home/dewes/bin/agent-memory", ""},
		{"manual windows", `C:\tools\agent-memory\agent-memory.exe`, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			pm, ok := DetectPackageManager(c.path)
			if c.wantName == "" {
				if ok {
					t.Fatalf("DetectPackageManager(%q) = %+v, want self-managed (ok=false)", c.path, pm)
				}
				return
			}
			if !ok || pm.Name != c.wantName {
				t.Fatalf("DetectPackageManager(%q) = (%+v, %v), want name %q", c.path, pm, ok, c.wantName)
			}
			if pm.Command == "" {
				t.Errorf("expected a non-empty upgrade command for %s", pm.Name)
			}
		})
	}
}
