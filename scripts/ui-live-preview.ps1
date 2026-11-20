# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# UI-LIVE-1: the live-reload preview of THIS checkout's app, for UI work the owner watches in a browser.
#
#   pwsh scripts/ui-live-preview.ps1                  # throwaway dev daemon + Vite dev server with hot reload
#   pwsh scripts/ui-live-preview.ps1 -Keep            # keep the temp state dir after it stops
#
# Saved in AgentsRoom as the dev command "Infantry live preview"; start and stop it there (commands_run / commands_stop).
# Isolation: the daemon is `cargo run -p infantry-daemon` built from this checkout, on a free loopback port, with a new
# state dir under %TEMP%\infantry-ui-preview\<stamp>. It never uses :18990, the live state dir, ~/.infantry, or the
# owner ports 8787/8788/8797/8798. INFANTRY_* / AGENTHQ_* are removed from the children's environment first.
# Vite (`pnpm dev` in app/) runs in the foreground from this checkout, so hot reload watches these files. When Vite ends
# (Ctrl+C, the tab closed, or a process-tree kill) the daemon is stopped by its PID tree and the state dir deleted.
#   pwsh scripts/ui-live-preview.ps1 -TargetDir D:\x   # another shared cargo target dir
#   pwsh scripts/ui-live-preview.ps1 -StagePlugins     # WEBUI-1: stage this checkout's official plugins and load them
#   pwsh scripts/ui-live-preview.ps1 -TestPlugins      # the same, plus the test plugins (rust/plugins/test)
#   pwsh scripts/ui-live-preview.ps1 -PluginsDir D:\p  # load a folder rust/plugins/stage.sh laid out
# Plugins: with none of the three, the dev daemon loads no plugins. Staged plugins go into the state dir.
# Disk: every worktree's preview builds into ONE shared cargo target dir outside the worktrees (default
# %LOCALAPPDATA%\infantry-dev\preview-target), so N UI workers cost one daemon build, not N copies of rust/target.
[CmdletBinding()]
param(
  [switch]$Keep,
  [string]$TargetDir = (Join-Path $env:LOCALAPPDATA 'infantry-dev\preview-target'),
  [string]$PluginsDir,
  [switch]$StagePlugins,
  [switch]$TestPlugins
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$ownerPorts = 8787, 8788, 8797, 8798, 18990

foreach ($v in [Environment]::GetEnvironmentVariables().Keys) {
  if ($v -like 'INFANTRY_*' -or $v -like 'AGENTHQ_*') { Remove-Item -LiteralPath "Env:$v" }
}

function Get-FreePort {
  while ($true) {
    $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop()
    if ($ownerPorts -notcontains $p) { return $p }
  }
}

$daemonPort = Get-FreePort
do { $uiPort = Get-FreePort } while ($uiPort -eq $daemonPort)
$previewRoot = Join-Path ([IO.Path]::GetTempPath()) 'infantry-ui-preview'
# A process-tree kill (commands_stop) skips `finally`, so earlier runs' state dirs are cleared here; one still in use is locked and stays.
Get-ChildItem -LiteralPath $previewRoot -Directory -ErrorAction SilentlyContinue |
  ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$state = Join-Path $previewRoot $stamp
New-Item -ItemType Directory -Force $state | Out-Null

. (Join-Path $repo 'scripts/rust-build-env.ps1')   # sccache
$env:CARGO_TARGET_DIR = $TargetDir                   # shared by every worktree's preview (cargo locks it per build)
$daemon = $null
$app = Join-Path $repo 'app'
try {
  if (-not (Test-Path (Join-Path $app 'node_modules'))) {
    Write-Host '[preview] installing app dependencies (pnpm install --frozen-lockfile)'
    pnpm -C $app install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw "pnpm install failed ($LASTEXITCODE)" }
  }
  if ($StagePlugins -or $TestPlugins) {
    # Git Bash, never C:\Windows\system32\bash.exe (that is WSL: no cargo, no python on its PATH).
    $gitRoot = Split-Path (Get-Command git).Source -Parent
    while ($gitRoot -and -not (Test-Path (Join-Path $gitRoot 'bin\bash.exe'))) { $gitRoot = Split-Path $gitRoot -Parent }
    $PluginsDir = Join-Path $state 'plugins'
    if ($TestPlugins) { $env:INFANTRY_STAGE_TEST = '1' }
    Write-Host "[preview] staging this checkout's plugins into $PluginsDir"
    & (Join-Path $gitRoot 'bin\bash.exe') (Join-Path $repo 'rust/plugins/stage.sh') $PluginsDir
    $staged = $LASTEXITCODE
    Remove-Item Env:INFANTRY_STAGE_TEST -ErrorAction SilentlyContinue
    if ($staged -ne 0) { throw "staging the plugins failed ($staged)" }
  }
  if ($PluginsDir) {
    $env:INFANTRY_PLUGINS_DIR = (Resolve-Path -LiteralPath $PluginsDir).Path
    Write-Host "[preview] the dev daemon loads the plugins in $env:INFANTRY_PLUGINS_DIR"
  }
  Write-Host "[preview] building (target $TargetDir) and starting the dev daemon on 127.0.0.1:$daemonPort (state $state)"
  $daemon = Start-Process cargo -ArgumentList 'run', '-q', '-p', 'infantry-daemon', '--', '--port', $daemonPort, '--state-dir', "`"$state`"" `
    -WorkingDirectory (Join-Path $repo 'rust') -NoNewWindow -PassThru
  $deadline = (Get-Date).AddMinutes(30)
  while ($true) {
    if ($daemon.HasExited) { throw "the dev daemon exited ($($daemon.ExitCode)) before it answered /health" }
    try { Invoke-RestMethod "http://127.0.0.1:$daemonPort/health" -TimeoutSec 2 | Out-Null; break } catch { }
    if ((Get-Date) -gt $deadline) { throw 'the dev daemon did not answer /health within 30 minutes' }
    Start-Sleep -Seconds 2
  }
  Write-Host "[preview] dev daemon up: http://127.0.0.1:$daemonPort/health"

  Remove-Item Env:INFANTRY_PLUGINS_DIR -ErrorAction SilentlyContinue   # the daemon's alone, not Vite's
  $env:INFANTRY_PORT = "$daemonPort"
  $env:INFANTRY_UI_PORT = "$uiPort"
  Write-Host "[preview] PREVIEW URL: http://localhost:$uiPort/   (daemon :$daemonPort)"
  pnpm -C $app dev
} finally {
  if ($daemon -and -not $daemon.HasExited) {
    Write-Host "[preview] stopping the dev daemon (pid $($daemon.Id) and its children)"
    & taskkill.exe /PID $daemon.Id /T /F | Out-Null
  }
  if (-not $Keep) { Remove-Item -LiteralPath $state -Recurse -Force -ErrorAction SilentlyContinue }
  Write-Host '[preview] stopped'
}
