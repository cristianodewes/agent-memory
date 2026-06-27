# agent-memory

[![CI](https://github.com/cristianodewes/agent-memory/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/cristianodewes/agent-memory/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> **Memória de longo prazo, movida a LLM, para agentes de código.**
> Um client em Go + um server em Spring Boot que capturam cada prompt, chamada de
> ferramenta e decisão que um agente toma, **compilam** tudo em uma base de
> conhecimento coerente em markdown com a ajuda de um LLM, e entregam à próxima
> sessão um briefing pronto de "onde você parou" — antes do primeiro prompt.

> **Status:** núcleo **implementado e mergeado na `main`**. Os marcos `M0`–`M9`
> (os ~40 issues `feat`) estão entregues: captura por hooks, consolidação por LLM,
> recall híbrido (FTS + grafo de links + vetorial), injeção de contexto no início do
> prompt, handoffs entre sessões/agentes, servidor MCP, API REST `/api/v1`, UI `/web`
> com "chat com a sua memória" (RAG), time-travel/backup, memória em camadas com
> *forget sweep*, segurança (loopback/bearer/Basic, multiusuário e **OIDC device**) e
> o laço de auto-melhoria. O empacotamento contínuo — **imagens Docker no GHCR** e
> **instalador nativo para Windows** — está em rollout (ver [Roadmap](#roadmap)).

---

## Sumário

- [O problema](#o-problema)
- [O que ele faz](#o-que-ele-faz)
- [Por que o LLM é obrigatório](#por-que-o-llm-é-obrigatório)
- [Arquitetura em resumo](#arquitetura-em-resumo)
- [Funcionalidades](#funcionalidades)
- [Instalação](#instalação)
- [Configuração](#configuração)
- [Uso e referência da CLI](#uso-e-referência-da-cli)
- [Ferramentas MCP](#ferramentas-mcp)
- [API REST `/api/v1`](#api-rest-apiv1)
- [UI web `/web`](#ui-web-web)
- [Layout do repositório](#layout-do-repositório)
- [Decisões de design](#decisões-de-design)
- [Roadmap](#roadmap)
- [Prior art](#prior-art)
- [Licença](#licença)

---

## O problema

Agentes de código baseados em LLM (Claude Code, Codex, Cursor, Gemini CLI, OpenCode, …)
perdem **todo** o contexto quando uma sessão termina. A sessão seguinte começa às cegas:
re-derivando decisões, relendo os mesmos arquivos, refazendo as mesmas perguntas. O
`agent-memory` dá aos agentes uma memória persistente, compartilhada e escopada por
projeto, de modo que o conhecimento se acumula entre sessões, ferramentas e máquinas.

## O que ele faz

1. **Captura** cada prompt, chamada de ferramenta e fronteira de sessão sem atrito
   (hooks de ciclo de vida — o usuário nunca digita um comando "salvar nota").
2. **Compila** essas observações cruas em páginas markdown duráveis e legíveis por humanos
   usando um LLM (o padrão *compile, não recupere*). A wiki é a **fonte da verdade**;
   o Postgres é um índice de busca **derivado**.
3. **Recupera** o conhecimento certo sob demanda via busca híbrida (texto completo + grafo
   de links + vetorial), re-ranqueada e curada pelo LLM, e **injeta** o contexto mais
   relevante no início de cada prompt.
4. **Faz o handoff** entre sessões e agentes: um briefing **escrito por LLM** (resumo,
   questões em aberto, próximos passos) é preparado no fim da sessão e injetado quando o
   próximo agente começa — mesmo que seja outra CLI no mesmo diretório.

### Compile, não recupere

O princípio central (DD-002): conhecimento compilado vive como **markdown em um
repositório git** (`wiki/`). É *grep*-ável, abre no Obsidian, faz backup com `rsync` e
viaja no tempo com `git`. O Postgres é apenas um índice **derivado** (FTS + vetorial +
relacional) que pode ser **reconstruído a qualquer momento** com `reindex`. Em vez de
recuperar logs crus em tempo de consulta, o sistema *compila* o ruído em páginas estáveis
de conceitos, decisões, *gotchas* e procedimentos.

> Exceção: as tabelas de captura (`sessions`, `observations`, `audit_log`) são primárias
> — não derivam da wiki — e são cobertas por `backup`/`restore`.

## Por que o LLM é obrigatório

O `agent-memory` **não** é uma ferramenta zero-LLM. Um provedor de LLM configurado é um
**requisito incondicional**, validado no startup (*fail-fast*): o server faz *probe* no
provedor de chat ao subir e **aborta** se nenhum estiver acessível (DD-005, invariante #13).
Não existe caminho de *fallback* baseado em regras a manter. Tornar o LLM central — em vez
de opcional — habilita capacidades que um motor de regras não alcança:

| Fluxo | O que o LLM faz |
|---|---|
| **Handoffs** | Lê as observações da sessão e escreve um "onde você parou" rico e preciso. |
| **Consolidação** | Transforma observações ruidosas em páginas limpas de conceito/decisão/gotcha; *fan-out* atômico de múltiplas páginas. |
| **Recall** | Expande consultas, re-ranqueia candidatos e cura o que é injetado no prompt. |
| **Curadoria** | Detecta contradições, sugere links e propõe auto-melhorias com *approval gates*. |
| **Chat** | "Chat com a sua memória" — responde perguntas ancoradas na wiki (RAG). |

Os provedores são plugáveis (Anthropic, OpenAI, qualquer endpoint OpenAI-compat/Ollama/vLLM,
Gemini, …), mas um provedor **precisa** estar configurado. **Embeddings** são um eixo
separado e *default-on*: se ausentes, o recall degrada graciosamente para FTS + grafo, mas
um LLM de chat/consolidação continua sendo mandatório. Ver
[`docs/design-decisions.md`](docs/design-decisions.md#dd-005).

## Arquitetura em resumo

```
 ┌───────────────┐   hooks de ciclo de vida + MCP (HTTP)    ┌───────────────────────────┐
 │ agente de IA  │ ──────────────────────────────────────▶ │  agent-memory (client Go) │
 │ Claude Code,  │ ◀──── handoff / recall injetados ─────── │  hooks · spool · drain    │
 │ Codex, Cursor │                                          │  CLI · instaladores       │
 └───────────────┘                                          └─────────────┬─────────────┘
                                                                          │ HTTP
                                                                          ▼
                                          ┌──────────────────────────────────────────────┐
                                          │        agent-memory server (Spring Boot)      │
                                          │  ingestão · sanitização · escritor único ·    │
                                          │  consolidação (LLM) · recall híbrido ·        │
                                          │  handoffs (LLM) · MCP · /api/v1 · /web (chat)  │
                                          └───────────────┬───────────────┬───────────────┘
                                            índice derivado │               │ exige
                                                            ▼               ▼
                                                  ┌──────────────┐   ┌───────────────┐
                                                  │  Postgres 16 │   │  provedor de  │
                                                  │ FTS+pgvector │   │  LLM (obrig.) │
                                                  └──────────────┘   └───────────────┘
                                                            ▲
                                        fonte da verdade    │ reindex (reconstruível)
                                                  ┌──────────────┐
                                                  │  wiki/ (git) │
                                                  └──────────────┘
```

A **wiki markdown em um repositório git é a fonte da verdade**; o Postgres é um índice
*derivado* que pode ser reconstruído a qualquer momento com `reindex`. O server é o
**único detentor de estado e lógica** (DD-001); o client Go é um *thin HTTP client* que
roda na máquina do desenvolvedor, no caminho quente do agente.

### Componentes

| Componente | Stack | Responsabilidade |
|---|---|---|
| **Client** | Go 1.23+ | Hooks de ciclo de vida, *spool* local + *drain*, CLI fina, instaladores de hooks/MCP, resolução cwd→projeto, *device flow* OIDC. |
| **Server** | Spring Boot 4.x (JDK 21+, Maven) | Fonte da verdade. Ingestão, sanitização, *store*, consolidação (LLM), recall, handoffs, MCP `/mcp`, `/api/v1`, `/web`, auth. |
| **Índice** | Postgres 16 + pgvector + tsvector | Índice derivado de texto completo (tsvector) + vetorial (pgvector) + tabelas relacionais (sessions, observations, links, handoffs, embeddings, audit_log). |
| **Wiki** | Markdown + git (JGit) | Fonte da verdade do conhecimento compilado. |
| **LLM** | Provedor plugável (obrigatório) | Consolidação, handoffs, re-rank/curadoria do recall, chat. |

### Layout do diretório de dados

O server materializa tudo sob um único *data dir* (default `~/.agent-memory`, resolvido para
caminho absoluto no startup):

```
<data_dir>/
├── wiki/     # fonte da verdade em markdown, um repositório git
│   └── <workspace>/<project>/
│       ├── sessions/  concepts/  decisions/  gotchas/  procedures/
│       ├── _rules/    _slots/    _lint/
│       └── log.md     # log de eventos por sessão, imutável
├── raw/      # arquivo cru e imutável das sessões
├── db/       # artefatos locais (opcional)
└── logs/     # tracing rotativo
```

## Funcionalidades

Todas as áreas abaixo já estão **implementadas e mergeadas na `main`**.

### Captura (hooks → spool → drain)

- **Hooks nativos** em Go (`agent-memory hook --event <kind>`): disparados pelos hooks de
  ciclo de vida do agente. Gravam o evento em um **spool local** em disco e retornam
  imediatamente (*fire-and-forget*, orçamento ≤200 ms) — nunca bloqueiam a rede no caminho
  quente. Eventos cobertos: `SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PostToolUse`,
  `Stop`, `SessionEnd` (nomes *agent-native* são canonicalizados internamente).
- **Drain nas fronteiras de sessão**: o spool é enviado ao server via `POST /hook` e
  `/hook/batch`. A ingestão aplica *backpressure* (`202` aceito / `429` sob saturação) com
  *backoff* exponencial; nada é apagado do spool antes do *ack* do server. Entradas
  corrompidas vão para `quarantine/` sem travar o lote.
- **Sanitização** como **fronteira tipada** (DD-010): texto não-confiável só chega ao
  *store* pelo construtor `sanitize()` — torna "este texto foi *privacy-stripped*?" uma
  garantia de tempo de compilação.

### Consolidação por LLM

- **Síntese de sessão** e **consolidação** são **sempre** via LLM (sem *fallback* de regras),
  com saída em JSON estruturado, *commit* na wiki/git e *reindex* das páginas afetadas na
  mesma transação lógica (escritor único, DD-006).
- Compila em páginas de `sessions/`, `concepts/`, `decisions/`, `gotchas/`, `procedures/`,
  com *fan-out* atômico de múltiplas páginas.

### Recall híbrido

- Funde **FTS (`tsvector`)** ⊕ **vizinhança no grafo de links** ⊕ **vetorial (`pgvector`)**
  via **Reciprocal Rank Fusion (RRF)**, com *fallback* para observações cruas.
- O braço vetorial usa distância de cosseno (operador `<=>` do pgvector) contra um índice
  HNSW, restrito ao par `(provider, model)` ativo. Default-on; degrada para FTS + grafo se
  não houver *embedder*.
- Em cima do RRF, o LLM faz **expansão de consulta**, **re-rank** dos candidatos e
  **curadoria** do que será injetado. Acessos reforçam *decay* (`access_count`,
  `last_accessed_at`).

### Injeção de contexto (o "recall-gap")

A captura é automática, mas o recall, sozinho, seria voluntário e cego. Por isso o contexto
é **empurrado** para o agente:

- **Prompt-time** (`UserPromptSubmit`): o client chama `POST /recall/inject` (timeout curto,
  advisory — nunca bloqueia o prompt) e injeta um bloco curado de top-N como *additional
  context*.
- **SessionStart**: monta um bloco de orientação com **handoff** + **briefing** + um **mapa
  de scent/orientação** do projeto, injetado antes do primeiro prompt.

### Handoffs entre sessões e agentes

- No fim da sessão, o LLM escreve um **handoff tipado** (resumo, questões em aberto, próximos
  passos), de **uso único**, e o injeta no `SessionStart` seguinte — mesmo que o próximo
  agente seja outra CLI no mesmo diretório. Tools MCP: `memory_handoff_begin` / `accept` /
  `cancel`.

### Memória em camadas, decay e forget

- Camadas **working / episodic / semantic / procedural** com **decay** por tempo e reforço
  por acesso.
- **Forget sweep** com isenção de itens *pinned* e modo `dry_run`.
- **Memory slots** (`_slots/`): páginas auto-fixadas e editáveis (`slot_kind: state | invariant`),
  surfaçadas no briefing/explore.

### Time-travel, backup e bootstrap

- **Checkpoints** (lista de *commits* da wiki) e **restore-page** (restaura uma página a
  partir de uma revisão git e reindexa).
- **Backup/restore** online do estado em banco (a fonte segue gravável durante o backup),
  protegidos por *guard* de processo vivo antes de operações destrutivas.
- **Bootstrap**: semeia a memória de um projeto a partir do histórico do repositório
  existente, em uma passada de LLM.

### Servidor MCP

- Endpoint **Streamable-HTTP em `/mcp`** (DD-003), hospedado pelo server. Expõe 16 tools de
  leitura/escrita (ver [Ferramentas MCP](#ferramentas-mcp)). O projeto é resolvido pela
  atividade recente de hooks da sessão.

### API e web

- **API REST `/api/v1`** (somente leitura, JSON) para frontends próprios.
- **UI `/web`**: navegador de markdown somente-leitura (lista de projetos, árvore de pastas,
  busca, render, grafo, *dark mode*) **+** "chat com a sua memória" ancorado na wiki (RAG, via
  SSE).

### Segurança

- **Loopback por default** (`127.0.0.1`, sem auth) — um laptop single-user não precisa de
  cerimônia (DD-007).
- **Bearer token** para expor o server; **HTTP Basic** em `/web` (o token vale como senha);
  *guard* de **allowed-hosts** (anti DNS-rebinding); **TLS por reverse proxy** (templates em
  `deploy/`).
- **Multiusuário**: tokens por usuário com *pepper* (SHA-256, o token nunca é persistido);
  atribuição de `actor` em observações/audit; admin via `user add/list/expire/revive/rotate-token`.
- **OIDC device flow (RFC 8628)** para os hooks nativos: o client obtém um JWT via *device
  grant*; o server valida assinatura (JWKS), *issuer*, *audience* e expiração.
- **Isolamento de escopo** (`auto_scope`): `single_slot` (default) / `per_actor` /
  `session_aware` — este último *fail-closed* (header `X-Agent-Memory-Session`).

### Auto-melhoria

- **Scheduler** não-sobreposto revisa sessões finalizadas fora do caminho quente e propõe
  edições por um **approval gate** (`pending_writes`).
- **Eval gates** executáveis (contrato JSON, **desligados por default**, nunca no caminho do
  hook). Tool MCP `memory_auto_improve` (`report` / `approve` / `reject`).

## Instalação

### Pré-requisitos

- **Go 1.23+** — para construir/rodar o client (ou baixe um binário pré-compilado).
- **JDK 21+** (JDK 25 funciona) e **Maven** — para o server. O Maven Wrapper (`mvnw`) é
  versionado, então o Maven do sistema é opcional.
- **Docker / Docker Compose** — para o Postgres e execuções locais ponta-a-ponta.
- **Postgres 16 + pgvector** — se rodar o banco fora do compose.
- **Credenciais de um provedor de LLM** — **obrigatórias** para subir o server (DD-005). Para
  experimentar offline, use o provedor determinístico `test`.

### 1. Subir o server (Docker Compose)

O stack local (server + Postgres com pgvector) vive em [`docker/compose.yml`](docker/compose.yml):

```bash
cp docker/.env.example docker/.env     # ajuste usuário/senha do Postgres
docker compose -f docker/compose.yml up --build
```

Isso roda as migrations do Flyway no startup e serve em `http://127.0.0.1:8080`. Por padrão o
compose usa o provedor de LLM **offline `test`** (sem chave, sem rede), para o stack subir sem
credenciais externas.

> **Para um provedor de LLM real:** defina `AGENT_MEMORY_LLM_AUTH_PROVIDER` (ex.: `anthropic`)
> e acrescente a chave correspondente ao serviço `server` do compose — o `compose.yml`
> empacotado encaminha apenas o **nome** do provedor, então adicione
> `AGENT_MEMORY_LLM_AUTH_API_KEY` (e, para embeddings, `AGENT_MEMORY_EMBEDDINGS_AUTH_PROVIDER`
> + `AGENT_MEMORY_EMBEDDINGS_AUTH_API_KEY`) ao bloco `environment:` do serviço. Alternativamente,
> monte um arquivo `agent-memory.yml` (ver [Configuração](#configuração)).

> **Persistência:** o compose persiste apenas o Postgres (volume `pgdata`). A **wiki** (fonte
> da verdade) fica no *data dir* do server (`~/.agent-memory` dentro do container); para
> mantê-la entre recriações do container, monte um volume nesse diretório.

#### Alternativa: rodar o server localmente (sem Docker)

```bash
cd server
./mvnw spring-boot:run        # exige Postgres acessível e um provedor de LLM configurado
```

### 2. Instalar o client

Baixe o binário `agent-memory` para o seu sistema na página de **Releases** (publicados via
goreleaser para Linux/macOS/Windows × amd64/arm64), ou construa a partir do código:

```bash
cd client
go build -o agent-memory ./cmd/agent-memory
```

### 3. Conectar o agente ao agent-memory

No diretório do seu projeto, o instalador idempotente conecta tudo de uma vez (alvo de
primeira classe: **Claude Code**):

```bash
agent-memory setup-agent --server-url http://127.0.0.1:8080
```

`setup-agent` faz, de forma idempotente:

- `install-hooks` — registra o hook de captura no `.claude/settings.json`;
- `install-mcp` — registra o server MCP no `.mcp.json` (entrada `http` para `<server>/mcp`);
- `install-instructions` — grava o *snippet* de *self-routing* no `CLAUDE.md` (ou `AGENTS.md`).

Use `agent-memory upgrade` para atualizar a fiação ao binário/config atuais e
`agent-memory uninstall` para remover tudo.

#### Escopo: projeto vs usuário — `--scope` / `--global`

Por padrão a fiação é **por projeto** (`--scope project`): grava em `<repo>/.claude/settings.json`
e `<repo>/.mcp.json`, então **cada repositório precisa do seu próprio install**. Para o Claude Code
você pode instalar **uma vez no escopo do usuário** (`--scope user`, ou o atalho `--global`) — a
fiação vai para `~/.claude/settings.json` (hooks), a config global `~/.claude.json` (MCP) e
`~/.claude/CLAUDE.md` (instruções), valendo para **todos** os repositórios:

```bash
agent-memory setup-agent --global --server-url http://127.0.0.1:8080   # recomendado
agent-memory uninstall   --global                                       # remove a fiação global
```

A identidade `(workspace, project)` continua sendo **resolvida em runtime**: os hooks derivam do
diretório de trabalho (git root / `.agent-memory.toml`) a cada evento, e no modo global o
`headersHelper` do MCP é gravado **sem** `--workspace`/`--project` fixos — o `mcp-session-header`
deriva por *cwd*/sessão. Quando o projeto não pode ser determinado, o server **falha fechado** (nada
de vazamento entre sessões; ver #87). Default permanece `project` (compatível); `user` é o caminho
**recomendado** e *opt-in*. (Escopo global para os demais agentes é fase 2.)

#### Multi-agente (`--agent` / `--client`)

Por padrão os instaladores miram o **Claude Code**. Passe `--agent <id>` (ou o alias
`--client <id>`) para mirar outro cliente: cada um recebe o *shape* de hook/MCP que espera, mas
todos apontam para o **mesmo binário nativo** (`agent-memory hook --event <kind>`) e o **mesmo
endpoint Streamable-HTTP** em `/mcp` com Bearer — só o que é gravado em cada config muda. Os
*shapes*, caminhos e nomes de evento por cliente **espelham a prior art ai-memory** (adaptados ao
binário nativo do agent-memory; sem scripts shell).

```bash
agent-memory setup-agent  --agent codex --server-url http://127.0.0.1:8080
agent-memory install-mcp   --agent cursor
agent-memory install-hooks --agent gemini-cli
```

| Cliente (`--agent`) | Hooks | MCP | Instruções |
|---|---|---|---|
| `claude-code` (padrão) | `<repo>/.claude/settings.json` — nested, 7 eventos (`SessionStart`…`PreCompact`…`SessionEnd`) | `<repo>/.mcp.json` — `mcpServers`, `type:http` + `headersHelper` (#87) | `CLAUDE.md` |
| `codex` | `~/.codex/hooks.json` — nested, 6 eventos (sem `SessionEnd`) | `~/.codex/config.toml` — `[mcp_servers.*]` (TOML, `http_headers` + `default_tools_approval_mode`) | `AGENTS.md` |
| `cursor` | `~/.cursor/hooks.json` — flat camelCase (`beforeSubmitPrompt`/`postToolUseFailure`/…) + `version:1` | `~/.cursor/mcp.json` — `mcpServers`, `url` | `AGENTS.md` |
| `gemini-cli` | `~/.gemini/settings.json` — nested (`SessionStart`/`SessionEnd`/`BeforeTool`/`AfterTool`/`PreCompress`) | `~/.gemini/settings.json` — `mcpServers`, `httpUrl` + `timeout` | `AGENTS.md` |
| `vscode-copilot` | — | `<repo>/.vscode/mcp.json` — `servers`, `type:http` | — |
| `claude-desktop` | — | config global do app — `mcpServers` via `mcp-remote` (stdio, header por env) | — |

`setup-agent --agent X` faz hooks + MCP + instruções para `X` numa tacada; uma *surface* que o
cliente não suporta (ex.: hooks no VS Code/Claude Desktop) é reportada como *unsupported* e
ignorada — não é erro. Todo merge é idempotente, preserva entradas de terceiros e usa escrita
atômica. Plugins TypeScript (opencode/omp/openclaw) e demais agentes (grok/antigravity) são fase 2.

> **Em rollout:** um **instalador nativo para Windows** (winget + EXE) e **imagens Docker
> publicadas no GHCR** via CD em cada *merge* na `main` estão planejados — ver [Roadmap](#roadmap).

## Configuração

### Configuração do server

O server combina, em ordem de precedência (menor primeiro): **defaults embutidos → arquivo
`agent-memory.yml` externo → variáveis de ambiente**. O arquivo externo é o `./agent-memory.yml`
no diretório de trabalho, ou o caminho apontado por `AGENT_MEMORY_CONFIG` (ou
`--agent-memory.config=<path>`). Veja o exemplo comentado em
[`server/agent-memory.yml.example`](server/agent-memory.yml.example).

Todas as chaves usam o prefixo `agent-memory`. As variáveis de ambiente seguem o *relaxed
binding* do Spring (`agent-memory.llm.auth.api-key` → `AGENT_MEMORY_LLM_AUTH_API_KEY`).

#### Provedor de LLM (obrigatório)

```yaml
agent-memory:
  llm:
    auth:
      provider: anthropic   # anthropic | openai | openai-compat | openai-oauth | gemini | test
      api-key: ""           # via env AGENT_MEMORY_LLM_AUTH_API_KEY (nunca hard-code aqui)
      model: ""             # opcional; default do provedor se ausente
      base-url: ""          # opcional; OBRIGATÓRIO para openai-compat
```

| `provider` | Modelo default | Endpoint default |
|---|---|---|
| `anthropic` | `claude-opus-4-8` | `https://api.anthropic.com` |
| `openai` | `gpt-5.5` | `https://api.openai.com/v1` |
| `openai-compat` | (sem default — defina `model`) | (sem default — defina `base-url`) |
| `openai-oauth` | `gpt-5.5` | `https://chatgpt.com/backend-api/codex/responses` (backend Codex) |
| `gemini` | `gemini-2.5-flash` | `https://generativelanguage.googleapis.com` |
| `test` | determinístico/offline | — (sem rede, sem chave) |

`openai-compat` fala o formato de fio da OpenAI e cobre OpenRouter, DeepSeek, Groq, Together,
Mistral, o endpoint OpenAI-compatível do Gemini e engines locais (Ollama, vLLM, LM Studio,
llama.cpp). Engines locais sem chave não enviam header `Authorization`. O server faz *probe*
no provedor no startup e **aborta** se ele estiver inacessível.

##### OpenAI via OAuth (`openai-oauth`) — assinatura ChatGPT em vez de API key

Quem tem assinatura ChatGPT (Plus/Pro/Team) pode autenticar com a credencial de **login** (OAuth
estilo Codex) em vez de uma chave de API paga por token. Um access token OAuth do ChatGPT **não** é
uma chave da Platform API: as requisições vão para o **backend Codex**
(`https://chatgpt.com/backend-api/codex/responses`, Responses API com streaming SSE) e incluem o
`chatgpt-account-id`. O server mantém um **access token fresco** renovando-o de forma transparente a
partir de um **refresh token** de longa duração (margem de 60 s antes do vencimento) e regrava o
token rotacionado — o server é o único detentor de estado e o único que chama o LLM (DD-001).

A credencial **não** é colada no YAML: ela vive em um **token file** (`<data-dir>/auth.json`, chave
`openai`) que o login do client escreve e o server lê/renova. A config só seleciona o provider:

```yaml
agent-memory:
  llm:
    auth:
      provider: openai-oauth
      model: gpt-5.5            # opcional (default gpt-5.5)
      # oauth:
      #   token-file: ""        # opcional; default <data-dir>/auth.json
      # base-url: ""            # opcional; sobrescreve o endpoint Codex Responses
```

O **login** é feito uma vez com o client, que roda o device flow da OpenAI (usercode → autorização no
browser → troca por tokens) e grava o `auth.json`:

```bash
agent-memory auth login openai-oauth        # use --data-dir para apontar ao data-dir do server
```

Aponte o `--data-dir` para o **data-dir do server** (no caso single-user co-localizado, o default
`~/.agent-memory` já coincide). O `client-id` do Codex, os endpoints de login/refresh e o backend
Codex são fixos no código (mesmos do Codex CLI / referência ai-memory).

> ⚠️ Os endpoints / `client-id` do OAuth de assinatura ChatGPT são **não-oficiais e podem mudar**;
> revise os termos de uso da OpenAI quanto a acesso programático com credencial de assinatura.
> Token file ausente, ou refresh token revogado/expirado, **aborta** o startup (fail-fast) com
> mensagem acionável (`auth login openai-oauth`). O caminho de API key (`openai`) permanece inalterado.

#### Embeddings (opcional, default-on)

```yaml
agent-memory:
  embeddings:
    auth:
      provider: voyage      # voyage | openai | google | test
      api-key: ""           # via env AGENT_MEMORY_EMBEDDINGS_AUTH_API_KEY
      model: ""             # opcional
```

Embedders: `voyage` (`voyage-3`), `openai` (`text-embedding-3-small`), `google`
(`gemini-embedding-001`) — todos emitem vetores de **1024 dimensões**, casando a coluna
`pgvector`. Sem embeddings, o recall degrada para FTS + grafo (aviso não-fatal no startup).

#### Postgres

```yaml
agent-memory:
  db:
    url: jdbc:postgresql://127.0.0.1:5432/agent_memory   # env AGENT_MEMORY_DB_URL
    username: agent_memory                                # env AGENT_MEMORY_DB_USERNAME
    password: ""                                          # env AGENT_MEMORY_DB_PASSWORD
```

Exige Postgres 16 com as extensões `vector` (pgvector) e `pg_trgm` — criadas pelas migrations
do Flyway no startup. (O `docker/compose.yml` usa usuário/banco `agentmemory` e sobrescreve
essas chaves de acordo.)

#### Autenticação, multiusuário e OIDC

```yaml
agent-memory:
  server:
    address: 127.0.0.1     # 0.0.0.0 só ao expor fora da loopback
    port: 8080
    base-path: /           # move /api/v1, /mcp, /web e /healthz juntos sob um prefixo
  auth:
    enabled: false         # false = loopback-only, sem token (DD-007)
    token: ""              # env AGENT_MEMORY_AUTH_TOKEN
    allowed-hosts: ""      # env AGENT_MEMORY_AUTH_ALLOWED_HOSTS (anti DNS-rebinding)
    token-pepper: ""       # env AGENT_MEMORY_AUTH_TOKEN_PEPPER — não-vazio liga o multiusuário
    oidc:
      issuer: ""           # vazio = OIDC desligado; ex.: https://accounts.google.com
      audience: ""         # obrigatório quando ligado (valida o claim aud)
      jwks-uri: ""         # opcional; descoberto via issuer se vazio
      principal-claim: sub # sub | email | preferred_username
  scope:
    auto: single_slot      # single_slot | per_actor | session_aware
```

Para expor o server: gere um token, habilite a auth e (se remoto) liste os *hosts*:

```bash
java -jar agent-memory-server.jar --generate-auth-token   # imprime um token e sai
```

Com `auth.enabled=true`, todas as rotas (`/mcp`, `/hook`, `/handoff`, `/api/v1`, ops admin,
`/web`) exigem `Authorization: Bearer <token>`; `/healthz` segue aberto. Habilitar auth sem
token **aborta** o startup. Em multiusuário, o `token` vira **root** (exigido nas ops admin) e
cada usuário recebe o seu próprio token.

### Configuração do client

O client resolve identidade e endpoint nesta ordem: arquivo *marker* `.agent-memory.toml` (no
ancestral mais próximo) → raiz do repositório git → diretório atual. O *marker* aceita
`workspace`, `project`, `server_url` e `token`.

| Variável de ambiente | Usada por | Default |
|---|---|---|
| `AGENT_MEMORY_SERVER_URL` | captura/drain e instaladores (`setup-agent`, `install-*`, flag `--server-url`) | `http://127.0.0.1:8080` |
| `AGENT_MEMORY_SERVER` | comandos administrativos/ops (`reindex`, `checkpoints`, `backup`, `user`, lifecycle; flag `--server`) | `http://127.0.0.1:8080` |
| `AGENT_MEMORY_TOKEN` | *bearer token* (captura, MCP e ops admin via `--token`) | — |
| `AGENT_MEMORY_DATA_DIR` | raiz do *data dir* local (spool, credencial OIDC, **logs**) | `~/.agent-memory` |
| `AGENT_MEMORY_LOG_LEVEL` | nível do log do client (`debug`\|`info`\|`warn`\|`error`) | `info` |
| `AGENT_MEMORY_DEBUG` | atalho que força nível `debug` quando *truthy* (`1`/`true`/`yes`/`on`) | — |
| `AGENT_MEMORY_LOG_RESPONSE_BODIES` | ⚠️ **opt-in de depuração** que grava o **corpo completo de cada resposta do server** no `client.log` (`debug`) quando *truthy* — **vaza conteúdo de memória**; ver aviso abaixo | — (off) |

> Atenção ao par de nomes: o caminho de captura/instaladores lê `AGENT_MEMORY_SERVER_URL`,
> enquanto os comandos administrativos leem `AGENT_MEMORY_SERVER` (sem o sufixo `_URL`).

#### Logs e modo *debug* do client

O client mantém um **log estruturado e rotativo** (JSON, um registro por linha) em
**`<data_dir>/logs/client.log`** — o equivalente, no client, ao *tracing* do server. Cada hook
registra o ciclo completo: evento capturado (kind, workspace/project), *append* no *spool*,
resultado do *drain* (quantos enviados, latência, status) e o *fetch* de handoff/briefing/recall —
além dos erros que antes só apareciam no `stderr` (visíveis apenas sob `claude --debug`). A escrita
é *fire-and-forget* (bufferizada, fora do *hot path*), então **não** afeta o orçamento de captura
(≤200 ms, invariante #5), e o `stdout` dos hooks continua limpo (a *additional-context* é preservada).

- **Nível**: `info` por *default*. Para ligar o *debug*, use a flag global `-v`/`--verbose`, ou
  `AGENT_MEMORY_LOG_LEVEL=debug`, ou `AGENT_MEMORY_DEBUG=1` (precedência: flag → `LOG_LEVEL` →
  `DEBUG` → `info`).
- **Segredos redigidos**: `token`/`Authorization` **nunca** são gravados; *payload* potencialmente
  sensível só aparece em `debug` e ainda passa pela redação (alinhado à sanitização do server —
  invariante #6 / DD-010).
- ⚠️ **Corpo das respostas (opt-in, off por default)**: `AGENT_MEMORY_LOG_RESPONSE_BODIES` *truthy*
  (`1`/`true`/`yes`/`on`) faz o client gravar, em `debug`, o **corpo completo de cada resposta do
  server** (recall/inject, briefing, handoff, scent) no `client.log`. **Isso vaza conteúdo de memória
  em texto puro** — use apenas para depuração deliberada, nunca como *default*. O corpo é *tee-ado*
  (os métodos tipados continuam parseando normalmente), truncado em ~64 KiB no log (com nota de
  truncamento) e **ainda passa pela redação**, então `token`/`Authorization` permanecem mascarados
  mesmo neste modo; só **headers** continuam fora do log (evita `Set-Cookie` etc.). (#126)
- **Rotação**: por tamanho (~5 MiB) com retenção limitada (`client.log.1`…`client.log.3`); o
  diretório é criado se faltar e respeita `AGENT_MEMORY_DATA_DIR`.
- **Inspeção**: `agent-memory logs` imprime/segue o arquivo e funciona como um *inspector*:
  - `-n`/`--tail N` (default 200; `0` = tudo) e `-f`/`--follow` (segue novas linhas; **resiliente a
    rotação** — quando o `client.log` é recriado/encolhe, o *follow* reabre o novo arquivo em vez de
    silenciar);
  - filtros combináveis (AND), aplicados ao *tail* **e** ao *follow*: `--level <error|warn|info|debug>`
    (nível mínimo), `--since <15m|2h|1d|RFC3339>`, `-g`/`--grep <regex>` e
    `--event <kind>` (com `--workspace` / `--project`) sobre os campos estruturados;
  - `-o`/`--format <json|text>` — `json` (cru, default, *machine-readable*) ou `text` (uma linha
    legível `ts · LEVEL · msg · campos`); `--no-color` desliga a cor (que só aparece em TTY);
  - `--path` imprime só o caminho absoluto do log e sai (scriptável; respeita
    `--data-dir`/`AGENT_MEMORY_DATA_DIR`).
  `agent-memory doctor` mostra *data dir*, nível efetivo, caminho do log, contagem de *spool* +
  quarentena e as últimas linhas — sem precisar de `cat`/`tail` manual.

## Uso e referência da CLI

O binário é `agent-memory`. Como o client é um *thin HTTP client*, leitura/escrita de memória
acontece pelas **tools MCP** e pela **injeção automática nos hooks** — não há comandos
`query`/`write`/`consolidate` na CLI. Os comandos reais:

**Instalação / fiação do agente**

Todos aceitam `--agent <id>` / `--client <id>` (default `claude-code`) — ver
[Multi-agente](#multi-agente---agent----client) — e `--scope project|user` / `--global`
(default `project`; `user` recomendado) — ver
[Escopo](#escopo-projeto-vs-usuário----scope----global).

| Comando | Descrição |
|---|---|
| `setup-agent` | Instala tudo para o agente: hooks + MCP + instruções (idempotente; default Claude Code). |
| `install-hooks` | Conecta o hook de captura na config de hooks do agente (default `.claude/settings.json`). |
| `install-mcp` | Registra o server MCP na config de MCP do agente (default `.mcp.json`). |
| `install-instructions` | Escreve o *snippet* de *self-routing* nas instruções do agente (`CLAUDE.md` / `AGENTS.md` / `GEMINI.md`). |
| `upgrade` | Atualiza hooks/MCP/instruções para o binário/config atuais. |
| `uninstall` | Remove hooks, MCP e instruções (do agente selecionado). |

**Captura (invocados pelos hooks, normalmente não manuais)**

| Comando | Descrição |
|---|---|
| `hook --event <kind> [--payload -]` | Captura um evento de ciclo de vida no *spool* local (*fire-and-forget*). |
| `mcp-session-header` | Emite o header `X-Agent-Memory-Session` (para `auto_scope=session_aware`). |

**Observabilidade**

| Comando | Descrição |
|---|---|
| `logs [-n N] [-f] [--level …] [--since …] [-g …] [--event …] [-o json\|text] [--path]` | Imprime, filtra e segue (resiliente a rotação) o log do client em `<data-dir>/logs/client.log`. |
| `doctor [--tail N]` | Saúde do client: *data dir*, nível de log, caminho do log, *spool* + quarentena e últimas linhas. |

**Índice**

| Comando | Descrição |
|---|---|
| `reindex [--full \| --incremental --since <ref>] [--reembed]` | Reconstrói o índice Postgres a partir da wiki (completo ou incremental). |

**Time-travel / backup**

| Comando | Descrição |
|---|---|
| `checkpoints [--limit N]` | Lista *commits* recentes da wiki (pontos de time-travel). |
| `restore-page --workspace --project --path --from <rev>` | Restaura uma página de uma revisão git e reindexa. |
| `backup --out <arquivo.tar.gz>` | Backup online (somente banco); a fonte segue gravável. |
| `restore --in <arquivo.tar.gz> [--force --yes]` | Restaura o estado do banco de um backup (destrutivo; *guarded*). |
| `bootstrap --workspace --project --repo <caminho>` | Semeia a memória a partir do histórico do repositório, via LLM. |

**Projetos (lifecycle)**

| Comando | Descrição |
|---|---|
| `rename-project --workspace --project --to` | Renomeia um projeto dentro do workspace. |
| `move-project --workspace --project --to-workspace [--to]` | Move (e opcionalmente renomeia) um projeto. |
| `purge-project --workspace --project --yes` | Apaga a subárvore da wiki e as linhas no banco (irreversível). |
| `reset [--force --yes]` | Apaga TODA a memória (banco + wiki); recusa enquanto um processo vivo segura o *data dir*. |

**Multiusuário (admin; exige token root)**

| Comando | Descrição |
|---|---|
| `user add --username` | Cria um usuário e emite o primeiro token (impresso uma única vez). |
| `user list` | Lista usuários e status. |
| `user expire --username` | Revoga o token de um usuário. |
| `user revive --username` | Reativa um usuário expirado. |
| `user rotate-token --username` | Emite um token novo, invalidando o anterior. |

**Autenticação (OIDC device flow)**

| Comando | Descrição |
|---|---|
| `auth login oidc-device --issuer --client-id [--scope]` | Login via *device authorization grant* (RFC 8628). |
| `auth status` | Mostra a credencial OIDC armazenada (issuer, subject, expiração). |
| `auth logout` | Remove a credencial OIDC armazenada. |

`version` imprime a versão do client. A maioria dos comandos aceita `--server`/`--server-url`
e `--token` para apontar/autenticar no server.

## Ferramentas MCP

Expostas em `/mcp` (Streamable-HTTP). Por padrão escopadas no projeto atual (resolvido pela
atividade recente de hooks); aceitam `scopes`/`global=true` para busca *cross-project*.

- **Leitura:** `memory_query`, `memory_recent`, `memory_read_page`, `memory_status`,
  `memory_briefing`, `memory_explore`, `memory_install_self_routing`.
- **Escrita:** `memory_write_page`, `memory_delete_page`.
- **Consolidação:** `memory_consolidate`.
- **Handoff:** `memory_handoff_begin`, `memory_handoff_accept`, `memory_handoff_cancel`.
- **Manutenção:** `memory_forget_sweep`, `memory_lint`, `memory_auto_improve`.

## API REST `/api/v1`

Somente leitura, JSON (handlers em `com.agentmemory.web`):

- `GET /workspaces` · `GET /projects`
- `GET /workspaces/{ws}/projects/{p}/pages` · `.../pages/{*path}`
- `GET /workspaces/{ws}/projects/{p}/recent` · `.../briefing` · `.../scent`
- `GET /workspaces/{ws}/overview`
- `GET /search` · `POST /search` (com `scopes`)
- `GET /graph`
- `POST /workspaces/{ws}/projects/{p}/chat` — "chat com a sua memória" (RAG via SSE,
  `text/event-stream`).

Rotas operacionais/admin ficam na raiz (fora de `/api/v1`): `/healthz`, `/hook`,
`/hook/batch`, `/handoff`, `/recall/inject`, `/reindex`, `/checkpoints`, `/restore-page`,
`/backup`, `/restore`, `/bootstrap`, `/projects/*`, `/reset`, `/users/*`, `/llm-test`, e o
servlet MCP em `/mcp`.

## UI web `/web`

SPA estática embutida no server (`classpath:/web-ui/`; customizável via
`--web-ui-dir`/`agent-memory.server.web-ui-dir`). Oferece navegação por workspace/projeto,
árvore de pastas, busca, render de markdown, grafo de links e *dark mode* — tudo
**somente-leitura** — mais um *drawer* **"Chat com a sua memória"** que consome o endpoint
SSE de chat. Escrita não-GET pelo browser é restrita a *same-origin*.

## Layout do repositório

```
agent-memory/
├── client/      # módulo Go — o binário `agent-memory` (hooks, spool, drain, CLI, instaladores, OIDC)
│   ├── cmd/agent-memory/      # entrypoint
│   └── internal/             # apiclient, cli, config, drain, handoff, hook, identity, install, oidc, orientation, spool, …
├── server/      # app Spring Boot (Maven) — fonte da verdade
│   ├── src/main/java/com/agentmemory/  # core, store, wiki, hooks, llm, consolidate, recall, mcp, web, security
│   └── src/main/resources/   # application.properties, db/migration (Flyway), prompts/, web-ui/
├── docker/      # compose.yml (server + Postgres), Dockerfile.server, .env.example
├── deploy/      # templates de reverse-proxy / TLS
├── docs/        # ARCHITECTURE.md, ROADMAP.md, design-decisions.md, contracts/
└── .github/     # workflows de CI/Release, templates de issue
```

## Decisões de design

As ADRs vivem em [`docs/design-decisions.md`](docs/design-decisions.md). Resumo:

| ID | Decisão |
|---|---|
| **DD-001** | Dois componentes: client Go (caminho quente) + server Spring Boot (única fonte da verdade). |
| **DD-002** | Markdown + git é a fonte da verdade; Postgres é índice derivado e reconstruível. |
| **DD-003** | MCP hospedado no server (Streamable-HTTP em `/mcp`). |
| **DD-004** | Postgres 16 + pgvector + tsvector como datastore único (sem vector DB à parte). |
| **DD-005** | **O LLM é dependência obrigatória** — validado no startup, *fail-fast* (a inversão deliberada). |
| **DD-006** | Disciplina de escritor único (writes serializados; índice *commita* com os dados). |
| **DD-007** | Loopback por default; *bearer* + Basic + allowed-hosts para expor. |
| **DD-008** | Monorepo (client + server com docs e CI compartilhados). |
| **DD-009** | Toolchain: Go 1.23+, JDK 21+, Maven, Flyway, JGit. |
| **DD-010** | Sanitização de privacidade como fronteira tipada. |

Documentação adicional: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) (arquitetura,
invariantes e fluxos de dados) e [`docs/ROADMAP.md`](docs/ROADMAP.md) (marcos M0–M9).

## Roadmap

O núcleo (M0–M9) está implementado na `main`. Em rollout, focando empacotamento/distribuição:

- **[#105] Instalador nativo para Windows** — empacotar o `agent-memory.exe` (winget + EXE via
  Inno Setup), adicionar ao `PATH` e oferecer o setup pós-instalação (hooks/MCP). *Uso
  pretendido:* instalação *one-click* no Windows. **Ainda não publicado.**
- **[#106] CD em *merge* na `main`** — a cada merge, *buildar* e publicar **imagens Docker do
  server no GHCR** (`ghcr.io/cristianodewes/agent-memory`, tags `latest`/`edge`/`sha-…`) e os
  binários do client (incluindo o instalador Windows) em um Release. *Uso pretendido:*
  `docker pull ghcr.io/cristianodewes/agent-memory`. **Ainda não publicado** — hoje a
  distribuição automatizada é o Release de binários do client via goreleaser (em *tags* `v*`).

## Prior art

O `agent-memory` é um produto *clean-room* inspirado por um levantamento de funcionalidades do
[`akitaonrails/ai-memory`](https://github.com/akitaonrails/ai-memory) e pelo padrão "LLM wiki"
de Karpathy (*compile, não recupere*), além de `agentmemory`, `basic-memory`, `cognee`,
`A-MEM` e o laço de auto-melhoria do Hermes Agent. A diferença central: **o LLM é obrigatório,
não opcional.**

## Licença

MIT © 2026 Cristiano Dewes — ver [`LICENSE`](LICENSE).
