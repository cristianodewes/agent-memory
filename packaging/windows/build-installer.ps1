#requires -Version 5.1
<#
.SYNOPSIS
    Builds the agent-memory Windows installer (Inno Setup) from a prebuilt binary.

.DESCRIPTION
    Reproducible, command-line entry point for producing agent-memory-<version>-setup.exe.
    This is what the CD workflow (issue #106) invokes after goreleaser has produced the
    Windows binary. It does NOT build Go code itself — it only packages an existing
    agent-memory.exe with Inno Setup (packaging\windows\agent-memory.iss).

    The binary is resolved, in order of precedence:
      1. -SourceBin <path-to-agent-memory.exe>
      2. -SourceZip <goreleaser windows .zip>  (the .exe is extracted from it)
      3. auto-discovery under client\dist (goreleaser snapshot/release output) for -Arch

.PARAMETER Version
    The release version, e.g. "1.2.3" or "1.2.3-rc1" (a leading "v" is accepted/stripped).

.PARAMETER SourceBin
    Explicit path to agent-memory.exe to package.

.PARAMETER SourceZip
    A goreleaser windows archive (agent-memory_<v>_windows_<arch>.zip) to extract the exe from.

.PARAMETER Arch
    Target architecture for auto-discovery and the output name. Default: x64.

.PARAMETER OutputDir
    Where the setup .exe is written. Default: packaging\windows\dist.

.PARAMETER IsccPath
    Path to ISCC.exe. Default: resolved from PATH, then the standard Inno Setup 6 location.

.EXAMPLE
    ./build-installer.ps1 -Version 1.2.3 -SourceBin C:\out\agent-memory.exe

.EXAMPLE
    ./build-installer.ps1 -Version 1.2.3 -SourceZip dist\agent-memory_1.2.3_windows_amd64.zip
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$SourceBin,
    [string]$SourceZip,

    [ValidateSet('x64', 'arm64')]
    [string]$Arch = 'x64',

    [string]$OutputDir,
    [string]$IsccPath
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
$IssPath = Join-Path $ScriptDir 'agent-memory.iss'

if (-not $OutputDir) { $OutputDir = Join-Path $ScriptDir 'dist' }

# --- Normalize the version + derive a numeric x.x.x.x file-version --------------------
$CleanVersion = $Version.TrimStart('v', 'V')
# Strip pre-release / build metadata for the numeric VersionInfo resource.
$NumericCore = ($CleanVersion -split '[-+]')[0]
$parts = @($NumericCore -split '\.')
while ($parts.Count -lt 4) { $parts += '0' }
$parts = $parts[0..3] | ForEach-Object { if ($_ -match '^\d+$') { $_ } else { '0' } }
$AppVersionInfo = ($parts -join '.')

# --- Resolve the source binary --------------------------------------------------------
function Resolve-SourceBinary {
    if ($SourceBin) {
        if (-not (Test-Path -LiteralPath $SourceBin)) { throw "SourceBin not found: $SourceBin" }
        return (Resolve-Path -LiteralPath $SourceBin).Path
    }

    if ($SourceZip) {
        if (-not (Test-Path -LiteralPath $SourceZip)) { throw "SourceZip not found: $SourceZip" }
        $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("agent-memory-iss-" + [System.Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $tmp -Force | Out-Null
        Expand-Archive -LiteralPath $SourceZip -DestinationPath $tmp -Force
        $exe = Get-ChildItem -Path $tmp -Filter 'agent-memory.exe' -Recurse | Select-Object -First 1
        if (-not $exe) { throw "agent-memory.exe not found inside $SourceZip" }
        return $exe.FullName
    }

    # Auto-discover goreleaser output: dist\agent-memory_windows_<goarch>...\agent-memory.exe
    $goarch = if ($Arch -eq 'arm64') { 'arm64' } else { 'amd64' }
    $distRoot = Join-Path $RepoRoot 'client\dist'
    if (Test-Path -LiteralPath $distRoot) {
        $candidate = Get-ChildItem -Path $distRoot -Filter 'agent-memory.exe' -Recurse |
            Where-Object { $_.FullName -match "windows_$goarch" } |
            Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    throw "Could not resolve a source binary. Pass -SourceBin or -SourceZip, or run goreleaser first (expected under $distRoot)."
}

# --- Resolve ISCC.exe -----------------------------------------------------------------
function Resolve-Iscc {
    if ($IsccPath) {
        if (-not (Test-Path -LiteralPath $IsccPath)) { throw "ISCC not found: $IsccPath" }
        return (Resolve-Path -LiteralPath $IsccPath).Path
    }
    $cmd = Get-Command iscc -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe'),
        # winget installs Inno Setup per-user here (no elevation), e.g. on CI runners.
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe')
    )
    foreach ($c in $candidates) { if ($c -and (Test-Path -LiteralPath $c)) { return $c } }
    throw "ISCC.exe not found. Install Inno Setup 6.3+ or pass -IsccPath. (winget install JRSoftware.InnoSetup)"
}

$bin = Resolve-SourceBinary
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$OutputDirFull = (Resolve-Path -LiteralPath $OutputDir).Path

Write-Host "agent-memory Windows installer build" -ForegroundColor Cyan
Write-Host "  version      : $CleanVersion (file-version $AppVersionInfo)"
Write-Host "  source bin   : $bin"
Write-Host "  arch         : $Arch"
Write-Host "  output dir   : $OutputDirFull"

$iscc = Resolve-Iscc
Write-Host "  ISCC         : $iscc"

$isccArgs = @(
    "/DAppVersion=$CleanVersion",
    "/DAppVersionInfo=$AppVersionInfo",
    "/DSourceBin=$bin",
    "/DOutputDir=$OutputDirFull",
    $IssPath
)

& $iscc @isccArgs
if ($LASTEXITCODE -ne 0) { throw "ISCC failed with exit code $LASTEXITCODE" }

$setup = Join-Path $OutputDirFull "agent-memory-$CleanVersion-setup.exe"
if (-not (Test-Path -LiteralPath $setup)) { throw "Expected installer not produced: $setup" }

$sha = (Get-FileHash -LiteralPath $setup -Algorithm SHA256).Hash
Write-Host ""
Write-Host "Built: $setup" -ForegroundColor Green
Write-Host "SHA256: $sha"
Write-Host ""
Write-Host "winget manifest fields:"
Write-Host "  InstallerSha256: $sha"
Write-Host "  InstallerUrl:    https://github.com/cristianodewes/agent-memory/releases/download/v$CleanVersion/agent-memory-$CleanVersion-setup.exe"

# Emit machine-readable outputs for CI (issue #106) to consume.
if ($env:GITHUB_OUTPUT) {
    "installer_path=$setup"   | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
    "installer_sha256=$sha"   | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
    "installer_version=$CleanVersion" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
}
