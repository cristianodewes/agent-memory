; agent-memory — native Windows installer (issue #105).
;
; Wraps the goreleaser-built `agent-memory.exe` (windows) into a standalone setup:
;   * installs the binary to a standard per-user (or, elevated, per-machine) location;
;   * adds the install dir to PATH (HKCU or HKLM depending on the install scope);
;   * optionally runs the post-install agent setup (hooks + MCP + self-routing) against
;     a project folder the user picks;
;   * ships a clean uninstaller that also strips the PATH entry.
;
; Reproducible by command line (this is what the CD workflow, issue #106, invokes):
;
;   iscc /DAppVersion=1.2.3 /DAppVersionInfo=1.2.3.0 ^
;        /DSourceBin="C:\path\to\agent-memory.exe" ^
;        /DOutputDir="dist" ^
;        packaging\windows\agent-memory.iss
;
; or via the helper: packaging\windows\build-installer.ps1 -Version 1.2.3 -SourceBin ...
;
; Requires Inno Setup 6.3+ (for the `x64compatible` architecture identifier).
; Encoded as UTF-8 (BOM) so the pt-BR custom messages keep their accents.

#ifndef AppVersion
  #define AppVersion "0.0.0-dev"
#endif
; Numeric file-version (x.x.x.x) for the EXE VersionInfo resource; the build script
; derives it from the semver tag (pre-release/build metadata stripped).
#ifndef AppVersionInfo
  #define AppVersionInfo "0.0.0.0"
#endif
; Path to the agent-memory.exe to package. Relative paths resolve against this script's
; directory; the default points at a local `go build` output for ad-hoc local builds.
#ifndef SourceBin
  #define SourceBin "..\..\client\agent-memory.exe"
#endif
#ifndef OutputDir
  #define OutputDir "dist"
#endif

#define AppName "agent-memory"
#define AppPublisher "Cristiano Dewes"
#define AppURL "https://github.com/cristianodewes/agent-memory"
#define AppExeName "agent-memory.exe"

[Setup]
; AppId is the stable identity of the product across versions — it MUST NOT change, or
; upgrades/uninstall correlation (and the winget `ProductCode`) break. Keep in lockstep
; with packaging/winget/.../*.installer.yaml (ProductCode = "{AppId}_is1").
AppId={{2DFEEE69-FF11-430B-841A-E88C6CB87DB8}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppURL}
AppSupportURL={#AppURL}/issues
AppUpdatesURL={#AppURL}/releases
VersionInfoVersion={#AppVersionInfo}
VersionInfoProductVersion={#AppVersionInfo}
VersionInfoCompany={#AppPublisher}
VersionInfoDescription={#AppName} client installer
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
AllowNoIcons=yes
OutputDir={#OutputDir}
OutputBaseFilename={#AppName}-{#AppVersion}-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; PATH is changed in [Code]; this makes Setup broadcast WM_SETTINGCHANGE so newly
; launched processes (new terminals) see the updated PATH without a reboot.
ChangesEnvironment=yes
; Default to a no-elevation per-user install; allow opting into all-users (HKLM PATH)
; via the UI or `/ALLUSERS` on the command line.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog commandline
; One x64 installer; runs natively on x64 and on ARM64 via x64 emulation.
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
UninstallDisplayName={#AppName} {#AppVersion}
UninstallDisplayIcon={app}\{#AppExeName}

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[CustomMessages]
english.AddToPathTask=Add agent-memory to the PATH (recommended)
english.SetupAgentTask=Configure Claude Code in a project now (hooks + MCP + self-routing)
english.ProjectPageCaption=Configure a Claude Code project
english.ProjectPageDescription=Choose the project folder to wire agent-memory into.
english.ProjectPageSubCaption=Setup writes .claude/settings.json, .mcp.json and the self-routing block into the folder. You can skip this and run "agent-memory setup-agent" in any project later.
english.ProjectPagePrompt=Project folder:
english.ConfiguringAgent=Configuring Claude Code in the selected project...
brazilianportuguese.AddToPathTask=Adicionar o agent-memory ao PATH (recomendado)
brazilianportuguese.SetupAgentTask=Configurar o Claude Code num projeto agora (hooks + MCP + self-routing)
brazilianportuguese.ProjectPageCaption=Configurar um projeto do Claude Code
brazilianportuguese.ProjectPageDescription=Escolha a pasta do projeto onde o agent-memory será configurado.
brazilianportuguese.ProjectPageSubCaption=O setup grava .claude/settings.json, .mcp.json e o bloco de self-routing na pasta. Você pode pular e rodar "agent-memory setup-agent" em qualquer projeto depois.
brazilianportuguese.ProjectPagePrompt=Pasta do projeto:
brazilianportuguese.ConfiguringAgent=Configurando o Claude Code no projeto selecionado...

[Tasks]
Name: "addtopath"; Description: "{cm:AddToPathTask}"
Name: "setupagent"; Description: "{cm:SetupAgentTask}"; Flags: unchecked

[Files]
Source: "{#SourceBin}"; DestDir: "{app}"; DestName: "{#AppExeName}"; Flags: ignoreversion

[Run]
; Optional, guided post-install setup. Runs the just-installed binary against the folder
; chosen on the custom page. Inno ignores the process exit code for [Run] entries, so a
; setup hiccup never fails the install — the binary is on PATH regardless and the user can
; re-run `agent-memory setup-agent` by hand. skipifsilent keeps winget/silent installs
; (which can't prompt for a folder) to binary + PATH only.
Filename: "{app}\{#AppExeName}"; Parameters: "setup-agent --dir ""{code:GetProjectDir}"""; \
  WorkingDir: "{code:GetProjectDir}"; StatusMsg: "{cm:ConfiguringAgent}"; \
  Flags: skipifsilent; Check: ShouldRunSetup

[Code]
const
  { Registry locations of the PATH value, per scope. }
  EnvKeyMachine = 'System\CurrentControlSet\Control\Session Manager\Environment';
  EnvKeyUser = 'Environment';

var
  ProjectDirPage: TInputDirWizardPage;

{ ---- PATH helpers ------------------------------------------------------------------ }

function EnvRootKey: Integer;
begin
  if IsAdminInstallMode then
    Result := HKEY_LOCAL_MACHINE
  else
    Result := HKEY_CURRENT_USER;
end;

function EnvSubKey: string;
begin
  if IsAdminInstallMode then
    Result := EnvKeyMachine
  else
    Result := EnvKeyUser;
end;

{ NeedsAddPath reports whether Dir is absent from the current PATH (case-insensitive,
  semicolon-delimited), so a re-install/upgrade never appends a duplicate. }
function NeedsAddPath(const Dir: string): Boolean;
var
  OrigPath: string;
begin
  if not RegQueryStringValue(EnvRootKey, EnvSubKey, 'Path', OrigPath) then
  begin
    Result := True;
    exit;
  end;
  Result := Pos(';' + Uppercase(Dir) + ';', ';' + Uppercase(OrigPath) + ';') = 0;
end;

procedure EnvAddPath(const Dir: string);
var
  OrigPath, NewPath: string;
begin
  if not NeedsAddPath(Dir) then
    exit;
  if not RegQueryStringValue(EnvRootKey, EnvSubKey, 'Path', OrigPath) then
    OrigPath := '';
  if OrigPath = '' then
    NewPath := Dir
  else if OrigPath[Length(OrigPath)] = ';' then
    NewPath := OrigPath + Dir
  else
    NewPath := OrigPath + ';' + Dir;
  { REG_EXPAND_SZ to match how Windows stores PATH (preserves %VAR% entries). }
  RegWriteExpandStringValue(EnvRootKey, EnvSubKey, 'Path', NewPath);
end;

procedure EnvRemovePath(const Dir: string);
var
  OrigPath: string;
  P: Integer;
begin
  if not RegQueryStringValue(EnvRootKey, EnvSubKey, 'Path', OrigPath) then
    exit;
  P := Pos(';' + Uppercase(Dir) + ';', ';' + Uppercase(OrigPath) + ';');
  if P = 0 then
    exit;
  if P > 1 then
    Dec(P);
  Delete(OrigPath, P, Length(Dir) + 1);
  RegWriteExpandStringValue(EnvRootKey, EnvSubKey, 'Path', OrigPath);
end;

{ ---- Optional post-install setup page ---------------------------------------------- }

procedure InitializeWizard;
begin
  ProjectDirPage := CreateInputDirPage(wpSelectTasks,
    ExpandConstant('{cm:ProjectPageCaption}'),
    ExpandConstant('{cm:ProjectPageDescription}'),
    ExpandConstant('{cm:ProjectPageSubCaption}'),
    False, '');
  ProjectDirPage.Add(ExpandConstant('{cm:ProjectPagePrompt}'));
end;

{ Show the folder picker only when the setup-agent task is checked. }
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if Assigned(ProjectDirPage) and (PageID = ProjectDirPage.ID) then
    Result := not WizardIsTaskSelected('setupagent');
end;

function GetProjectDir(Param: string): string;
begin
  Result := ProjectDirPage.Values[0];
end;

{ Only run setup-agent when the task is on AND a real folder was supplied. }
function ShouldRunSetup: Boolean;
var
  Dir: string;
begin
  Result := False;
  if not WizardIsTaskSelected('setupagent') then
    exit;
  Dir := Trim(ProjectDirPage.Values[0]);
  Result := (Dir <> '') and DirExists(Dir);
end;

{ ---- Lifecycle hooks --------------------------------------------------------------- }

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    if WizardIsTaskSelected('addtopath') then
      EnvAddPath(ExpandConstant('{app}'));
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  { Always attempt removal — idempotent if the entry is already gone. }
  if CurUninstallStep = usPostUninstall then
    EnvRemovePath(ExpandConstant('{app}'));
end;
