# Instalador nativo de Windows (Inno Setup)

Empacota o binário `agent-memory.exe` (produzido pelo goreleaser) num instalador `.exe`
standalone para Windows, com PATH e desinstalador limpo. É a base do pacote **winget**
(ver [`../winget/`](../winget/)), que apenas embrulha este EXE.

## O que o instalador faz

1. Copia `agent-memory.exe` para um local padrão:
   - **por usuário** (padrão, sem UAC): `%LOCALAPPDATA%\Programs\agent-memory`;
   - **por máquina** (elevado): `%ProgramFiles%\agent-memory`.
2. Adiciona o diretório de instalação ao `PATH` (em `HKCU` no modo por usuário, `HKLM` no
   modo por máquina) e dispara `WM_SETTINGCHANGE`, então um **novo** terminal já enxerga o
   `agent-memory` sem reiniciar. A adição é idempotente (não duplica em upgrade/reinstalação).
3. Oferece, opcionalmente, rodar o **setup pós-instalação** (`agent-memory setup-agent`)
   numa pasta de projeto escolhida no assistente — isso grava `.claude/settings.json`,
   `.mcp.json` e o bloco de self-routing nas instruções do agente. Desmarcado por padrão.
4. Gera um **desinstalador** (em "Aplicativos instalados") que remove o binário e retira a
   entrada do `PATH`.

> O desinstalador **não** mexe nas configs por projeto (`.claude/`, `.mcp.json`) nem no
> data dir `~/.agent-memory` — são dados do usuário. Para limpar um projeto, rode
> `agent-memory uninstall` nele; o data dir pode ser apagado manualmente.

## Arquivos

| Arquivo | Função |
|---|---|
| `agent-memory.iss` | Script Inno Setup (UTF-8 com BOM; parametrizado via `/D`). |
| `build-installer.ps1` | Build reproduzível por linha de comando (resolve binário + ISCC, calcula versão, gera o `setup.exe` e imprime o SHA256). |

## Como construir

Pré-requisitos: **Inno Setup 6.3+** (pelo identificador de arquitetura `x64compatible`) e
um `agent-memory.exe` de windows já compilado.

```powershell
# A partir de um binário já compilado:
pwsh packaging/windows/build-installer.ps1 -Version 1.2.3 -SourceBin C:\out\agent-memory.exe

# Ou a partir do .zip do goreleaser:
pwsh packaging/windows/build-installer.ps1 -Version 1.2.3 `
     -SourceZip dist/agent-memory_1.2.3_windows_amd64.zip
```

Saída: `packaging/windows/dist/agent-memory-1.2.3-setup.exe` e o `SHA256` (que alimenta o
manifesto winget). Em CI (issue #106), o script também escreve `installer_path`,
`installer_sha256` e `installer_version` em `$GITHUB_OUTPUT`.

Invocação direta do compilador (equivalente):

```powershell
iscc /DAppVersion=1.2.3 /DAppVersionInfo=1.2.3.0 `
     /DSourceBin="C:\out\agent-memory.exe" /DOutputDir="dist" `
     packaging\windows\agent-memory.iss
```

Instalar o Inno Setup: `winget install JRSoftware.InnoSetup`.

## Instalação (uso final)

```powershell
# Silencioso, por usuário (sem UAC):
agent-memory-1.2.3-setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART

# Por máquina (todos os usuários, requer elevação):
agent-memory-1.2.3-setup.exe /ALLUSERS
```

## O que foi testado vs. validação manual

**Testado automaticamente** (nesta máquina, por linha de comando):
- `go build` do client (windows/amd64) — compila limpo.
- `agent-memory setup-agent --dir <pasta>` (o comando exato que a etapa `[Run]` executa) —
  retorna 0 e cria `.claude/settings.json`, `.mcp.json` e `CLAUDE.md`.
- `build-installer.ps1` — parsing de versão (`v1.2.3-rc1` → file-version `1.2.3.0`),
  resolução de binário e mensagens de erro (binário/ISCC ausentes).

**Pendente de validação manual numa máquina Windows com Inno Setup** (não há ISCC/GUI aqui):
- Compilar o `.iss` com `iscc` (ou `build-installer.ps1`) e gerar o `setup.exe`.
- Rodar o instalador (por usuário e por máquina) e conferir: binário no PATH em um novo
  terminal; a página de pasta de projeto aparece só com a tarefa marcada; desinstalação
  remove binário e a entrada de PATH.
- Render dos acentos pt-BR no assistente (depende do BOM UTF-8 — já presente no `.iss`).

## Follow-ups declarados
- **Assinatura de código** (Authenticode) do `setup.exe` — fora de escopo (issue #105).
- **Instalador arm64 nativo** — hoje o EXE x64 roda em ARM64 via emulação.
