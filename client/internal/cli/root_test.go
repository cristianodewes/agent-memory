package cli

import "testing"

func TestRootHasVersionCommand(t *testing.T) {
	root := newRootCmd()
	for _, c := range root.Commands() {
		if c.Name() == "version" {
			return
		}
	}
	t.Fatal("expected a 'version' subcommand on the root command")
}

func TestVersionIsSet(t *testing.T) {
	if Version == "" {
		t.Fatal("Version must not be empty")
	}
}
