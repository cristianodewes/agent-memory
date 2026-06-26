# Pacote winget

Manifesto do **Windows Package Manager** (winget) para o client `agent-memory`. O pacote
embrulha o instalador Inno Setup (ver [`../windows/`](../windows/)): com `InstallerType:
inno`, o winget ganha PATH e desinstalação limpos de graça e aplica os switches silenciosos
padrão (`/VERYSILENT /SUPPRESSMSGBOXES /NORESTART`).

## Layout

```
manifests/c/cristianodewes/agent-memory/<versão>/
├── cristianodewes.agent-memory.yaml               # version
├── cristianodewes.agent-memory.installer.yaml     # installer (URL + SHA256 + inno)
└── cristianodewes.agent-memory.locale.en-US.yaml  # defaultLocale
```

`0.1.0/` é um **template** — a automação de release (issue #106) gera uma pasta por versão.

## Instalar (uso final)

```powershell
winget install cristianodewes.agent-memory
```

## Atualizar por release

1. Copie a pasta da versão anterior para `manifests/c/cristianodewes/agent-memory/<nova>/`.
2. Em todos os 3 arquivos: ajuste `PackageVersion`.
3. No `*.installer.yaml`: ajuste `InstallerUrl` (tag `v<nova>`), `InstallerSha256` (o SHA256
   impresso por `build-installer.ps1`) e `ReleaseDate`.
4. **Não** mude o `PackageIdentifier` nem o `ProductCode` (o GUID precisa casar com o `AppId`
   em `../windows/agent-memory.iss`).

## Validar

```powershell
winget validate --manifest manifests/c/cristianodewes/agent-memory/0.1.0
```

> Validado nesta máquina (winget v1.29): **"Êxito na validação do manifesto"**.

Teste de instalação real (numa máquina Windows, após publicar a release):

```powershell
winget install --manifest manifests/c/cristianodewes/agent-memory/0.1.0
```

## Submeter ao repositório público

Para aparecer em `winget install`, o manifesto vai para
[`microsoft/winget-pkgs`](https://github.com/microsoft/winget-pkgs) (via
[`wingetcreate`](https://github.com/microsoft/winget-create) ou PR). Isso é parte do CD
(issue #106); a automação pode usar `wingetcreate update` com o novo `InstallerUrl`/SHA256.

## Pendente de validação manual
- `winget install` ponta-a-ponta a partir de uma release publicada (precisa do `setup.exe`
  no GitHub Releases e do SHA256 real no manifesto).
