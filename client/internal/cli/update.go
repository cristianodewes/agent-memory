package cli

import (
	"context"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/cristianodewes/agent-memory/client/internal/selfupdate"
	"github.com/spf13/cobra"
)

// newUpdateCmd builds the `agent-memory update` subcommand (alias `self-update`): an
// on-demand, in-place updater for the client binary itself. It is distinct from
// `upgrade`, which only refreshes the installed hooks/MCP/instructions config.
//
// It never auto-updates — it acts only when the user runs it — and refuses to overwrite
// a binary owned by a package manager (winget/Scoop/Homebrew), pointing at that manager
// instead.
func newUpdateCmd() *cobra.Command {
	var (
		checkOnly bool
		version   string
		channel   string
		apiURL    string
	)

	cmd := &cobra.Command{
		Use:     "update",
		Aliases: []string{"self-update"},
		Short:   "Atualiza o binário agent-memory para a última versão dos GitHub Releases",
		Long: "Atualiza o próprio binário agent-memory.\n\n" +
			"Consulta os GitHub Releases do projeto, compara com a versão embutida neste binário e, " +
			"havendo versão mais nova, baixa o artefato do seu sistema (SO) e arquitetura, valida o " +
			"checksum (checksums.txt) e substitui o binário em uso de forma atômica e com rollback.\n\n" +
			"Nunca atualiza sozinho: só age quando você o executa. Se o binário foi instalado por um " +
			"gerenciador de pacotes (winget, Scoop, Homebrew), ele NÃO sobrescreve o arquivo e orienta " +
			"a atualizar pelo próprio gerenciador.",
		Args:          cobra.NoArgs,
		SilenceUsage:  true,
		SilenceErrors: true,
		RunE: func(cmd *cobra.Command, _ []string) error {
			ch := selfupdate.Channel(channel)
			if ch != selfupdate.ChannelStable && ch != selfupdate.ChannelEdge {
				return fmt.Errorf("canal inválido %q: use stable ou edge", channel)
			}
			updater, err := selfupdate.New(selfupdate.Options{
				CurrentVersion: Version,
				Channel:        ch,
				PinnedVersion:  version,
				APIBaseURL:     apiURL,
				Token:          githubToken(),
			})
			if err != nil {
				return err
			}
			return runUpdate(cmd.Context(), cmd.OutOrStdout(), updater, checkOnly)
		},
	}

	cmd.Flags().BoolVar(&checkOnly, "check", false,
		"apenas verifica e reporta se há atualização, sem aplicar")
	cmd.Flags().StringVar(&version, "version", "",
		"instala uma versão específica (ex.: 1.3.0) em vez da mais recente")
	cmd.Flags().StringVar(&channel, "channel", string(selfupdate.ChannelStable),
		"canal de releases: stable (estável) ou edge (inclui prereleases)")
	cmd.Flags().StringVar(&apiURL, "api-url", "",
		"base da API do GitHub (uso interno/testes; padrão api.github.com)")
	_ = cmd.Flags().MarkHidden("api-url")
	return cmd
}

// runUpdate resolves the target release and, unless --check, applies it — refusing to
// overwrite a package-manager-owned binary.
func runUpdate(ctx context.Context, out io.Writer, updater *selfupdate.Updater, checkOnly bool) error {
	plan, err := updater.Check(ctx)
	if err != nil {
		return err
	}

	if plan.UpToDate {
		fmt.Fprintf(out, "agent-memory %s já é a versão mais recente disponível; nada a fazer.\n",
			plan.CurrentVersion)
		return nil
	}

	fmt.Fprintf(out, "Atualização disponível: %s → %s.\n", plan.CurrentVersion, plan.TargetVersion)
	if checkOnly {
		return nil
	}

	if pm, ok := selfupdate.DetectPackageManager(updater.ExecPath()); ok {
		fmt.Fprintf(out, "Este binário parece ter sido instalado via %s:\n    %s\n",
			pm.Name, updater.ExecPath())
		fmt.Fprintf(out, "Atualize pelo gerenciador para manter a instalação consistente:\n    %s\n",
			pm.Command)
		return nil
	}

	return updater.Apply(ctx, out, plan)
}

// githubToken returns a GitHub token for private-repo asset access, checking the
// agent-memory-specific variable first, then the conventional GitHub ones. Empty when
// none is set (anonymous access, fine for public releases).
func githubToken() string {
	for _, key := range []string{"AGENT_MEMORY_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN"} {
		if v := strings.TrimSpace(os.Getenv(key)); v != "" {
			return v
		}
	}
	return ""
}
