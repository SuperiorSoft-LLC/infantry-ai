# SPDX-License-Identifier: MPL-2.0
# BOS-31 / DEV-POLISH-1: develop Infantry AI inside a BattalionOS DEVELOPER build, with live reload and no package, signature, catalog or install.
#
#   scripts/bos-dev-app.ps1 -Os <BattalionOS checkout> [-Watch]     start (a second start while one runs says so)
#   scripts/bos-dev-app.ps1 -Stop                                   stop everything it started (OS, app, dev servers, watcher), by pid
#
# One command: builds the app library in dev form (cargo build -p infantry-distribution), lays the app folder (app.json, lib/, the page
# entry), starts the page's dev server (app/shell, Vite) on a free port of this PC's loopback only, and starts the BattalionOS dev build
# (scripts/bos-dev.ps1 of that checkout, built into its own target folder) with the folder registered (`--dev-app <folder> --dev-url <server>`).
#   - A change in app/shell/src is seen in the OS window by the page's own hot reload; the OS is not touched.
#   - -Watch: a change in rust/ rebuilds the library only and copies it into the folder; the OS stops the app the normal way and starts the
#     new one (same OS process, other apps untouched). The OS is not rebuilt.
# Everything started here runs with INFANTRY_*, AGENTHQ_* and BATTALION_* removed. Dev mode has its own data folder for the OS and the app
# (never %LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust). Loopback only. Logs: <DevRoot>\logs.
param(
  [string]$Os = '',
  [string]$Folder = (Join-Path (Split-Path $PSScriptRoot -Parent) 'dist-bosdev'),
  [string]$DevRoot = (Join-Path $env:TEMP 'bos-dev-ia'),
  [string]$OsTargetDir = '',   # default: the OS script's own folder (%LOCALAPPDATA%\bos-dev-target\os)
  [int]$MinFreeGb = 8,
  [switch]$Watch,
  [switch]$Stop
)
$ErrorActionPreference = 'Stop'
Get-ChildItem Env: | Where-Object { $_.Name -match '^(INFANTRY_|AGENTHQ_|BATTALION_)' } | ForEach-Object { Remove-Item "Env:$($_.Name)" }
$repo = Split-Path $PSScriptRoot -Parent
$lib = 'infantry_distribution.dll'
$logs = Join-Path $DevRoot 'logs'
$pidFile = Join-Path $DevRoot 'ia-pids.json'
function Fail([string]$why) { Write-Host "bos-dev-app: $why" -ForegroundColor Red; exit 1 }
function Test-Ours($p) { if (-not $p) { return $false }; $x = Get-Process -Id $p.id -ErrorAction SilentlyContinue; [bool]($x -and $x.StartTime.Ticks -eq [int64]$p.start) }
function Mark($proc) { [pscustomobject]@{ id = $proc.Id; start = (Get-Process -Id $proc.Id).StartTime.Ticks } }
function Stop-Ours($p) { if (Test-Ours $p) { & taskkill.exe /PID $p.id /T /F 2>&1 | Out-Null; return $true }; $false }
function Os-Script($checkout) { Join-Path $checkout 'scripts\bos-dev.ps1' }

if ($Stop) {
  $done = @()
  if (Test-Path $pidFile) {
    $j = Get-Content $pidFile -Raw | ConvertFrom-Json
    foreach ($k in 'watcher', 'vite') { if (Test-Ours $j.$k) { $done += $k }; Stop-Ours $j.$k | Out-Null }   # the watcher first: it would rebuild and copy again
    if ($j.os -and (Test-Path (Os-Script $j.os))) {
      $out = & pwsh -NoProfile -File (Os-Script $j.os) -Stop -DevRoot $DevRoot
      if ($out -match 'stopped (?!nothing)') { $done += 'OS and app' }
    }
    Remove-Item $pidFile
  }
  Write-Host ("bos-dev-app: " + $(if ($done) { 'stopped ' + ($done -join ', ') } else { 'nothing was running' }) + '. Its data stays in ' + $DevRoot + ' until you delete it.')
  return
}
if (Test-Path $pidFile) {
  $j = Get-Content $pidFile -Raw | ConvertFrom-Json
  if (Test-Ours $j.vite) { Write-Host "bos-dev-app: it is already running (the page is at http://127.0.0.1:$($j.port)); not starting a second. To stop it: scripts/bos-dev-app.ps1 -Stop -DevRoot `"$DevRoot`""; return }
  Remove-Item $pidFile
}
if (-not $Os) { Fail 'say which BattalionOS checkout to use: scripts/bos-dev-app.ps1 -Os C:\path\to\BattalionOS' }
if (-not (Test-Path (Os-Script $Os))) { Fail "no scripts\bos-dev.ps1 in $Os. Point -Os at a BattalionOS checkout." }
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) { Fail 'cargo (Rust) is not installed or not on PATH. Install Rust from https://rustup.rs, open a new terminal, and run this again.' }
if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) { Fail 'pnpm is not installed or not on PATH. Install it (npm install -g pnpm), open a new terminal, and run this again.' }
New-Item -ItemType Directory -Force $logs | Out-Null
$freeGb = [math]::Floor((Get-Item $logs).PSDrive.Free / 1GB)
if ($freeGb -lt $MinFreeGb) { Fail "only $freeGb GB free on $((Get-Item $logs).PSDrive.Name):, a dev build needs $MinFreeGb GB. Free some space and run this again." }
$t0 = Get-Date

function Build-Lib {
  Push-Location (Join-Path $repo 'rust')
  try {
    & cargo build -p infantry-distribution *> (Join-Path $logs 'app-build.log')
    if ($LASTEXITCODE -ne 0) { throw "the app did not build; the errors are at the end of $(Join-Path $logs 'app-build.log')" }
    $target = (& cargo metadata --format-version 1 --no-deps | ConvertFrom-Json).target_directory
  } finally { Pop-Location }
  $built = Join-Path $target "debug\$lib"
  if (-not (Test-Path $built)) { throw "the built app library is missing: $built" }
  New-Item -ItemType Directory -Force (Join-Path $Folder 'lib') | Out-Null
  Copy-Item $built (Join-Path $Folder "lib\$lib") -Force
}

# The folder: the manifest as the package has it (version from the app's tauri.conf.json), the library, the page entry (only used when no dev server).
$conf = Get-Content (Join-Path $repo 'app\src-tauri\tauri.conf.json') -Raw | ConvertFrom-Json
$m = Get-Content (Join-Path $repo 'packaging\app.json') -Raw | ConvertFrom-Json
$m.version = $conf.version
New-Item -ItemType Directory -Force (Join-Path $Folder 'shell\dist'), (Join-Path $Folder 'assets') | Out-Null
$m | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $Folder 'app.json') -Encoding utf8
Copy-Item (Join-Path $repo 'app\src-tauri\icons\128x128@2x.png') (Join-Path $Folder 'assets\icon.png') -Force
Set-Content (Join-Path $Folder 'shell\dist\index.html') '<p>Infantry AI is shown from its dev server in developer mode.</p>'
Write-Host 'Building the app (only what changed; the first build takes several minutes)...'
try { Build-Lib } catch { Fail "$_" }

# The page's dev server, loopback only.
function Free-Port { $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0); $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop(); $p }
$port = Free-Port
# The app's own daemon port: a free loopback port, never the default one of the installed app (18990).
$daemonPort = Free-Port
if (-not (Test-Path (Join-Path $repo 'app\shell\node_modules'))) {
  Write-Host 'Installing the page packages (first start only)...'
  Push-Location (Join-Path $repo 'app'); try { & pnpm install --frozen-lockfile *> (Join-Path $logs 'pnpm-install.log') } finally { Pop-Location }
  if ($LASTEXITCODE -ne 0) { Fail "pnpm install failed (no network?). See $(Join-Path $logs 'pnpm-install.log'), fix it, and run this again." }
}
$psi = [Diagnostics.ProcessStartInfo]::new('cmd.exe', "/c pnpm exec vite --host 127.0.0.1 --port $port --strictPort > `"$(Join-Path $logs 'page.log')`" 2>&1")
$psi.WorkingDirectory = Join-Path $repo 'app\shell'; $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
$psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true   # the log files get the output; the terminal (or a pipe reading this script) is not held open by it
$psi.Environment['INFANTRY_UI_PORT'] = "$port"   # the page's own server setting (app/shell/vite.config.ts), set for it alone
$vite = [Diagnostics.Process]::Start($psi)
$up = $false
for ($i = 0; $i -lt 150 -and -not $up -and -not $vite.HasExited; $i++) { try { $c = [Net.Sockets.TcpClient]::new('127.0.0.1', $port); $c.Close(); $up = $true } catch { Start-Sleep -Milliseconds 200 } }
if (-not $up) { & taskkill.exe /PID $vite.Id /T /F 2>&1 | Out-Null; Fail "the page's dev server did not start on port $port (taken, or an error). Run this again; details: $(Join-Path $logs 'page.log')" }
New-Item -ItemType Directory -Force $DevRoot | Out-Null
$watcher = $(if ($Watch) { Mark (Get-Process -Id $PID) } else { $null })
[pscustomobject]@{ vite = (Mark $vite); watcher = $watcher; port = $port; folder = $Folder; os = $Os } | ConvertTo-Json | Set-Content $pidFile

# The OS, with the folder registered.
$osArgs = @('-NoProfile', '-File', (Os-Script $Os), '-App', $Folder, '-AppUrl', "http://127.0.0.1:$port", '-DevRoot', $DevRoot, '-AppEnv', "INFANTRY_PORT=$daemonPort", '-Open', 'infantry-ai', '-MinFreeGb', "$MinFreeGb", '-Quiet')
if ($OsTargetDir) { $osArgs += @('-OsTargetDir', $OsTargetDir) }
& pwsh @osArgs
if ($LASTEXITCODE -ne 0) { & taskkill.exe /PID $vite.Id /T /F 2>&1 | Out-Null; Remove-Item $pidFile -ErrorAction SilentlyContinue; Fail 'the OS did not start (see the line above).' }
$osState = Get-Content (Join-Path $DevRoot 'pids.json') -Raw | ConvertFrom-Json

Write-Host ''
Write-Host "Running now: Infantry AI inside a dev BattalionOS window (pid $($osState.os.id)), and the page's dev server (http://127.0.0.1:$port, this PC only)."
Write-Host "It uses its own data in $DevRoot, never your installed Infantry AI, BattalionOS or tools. The app's own daemon is on a free port of this PC ($daemonPort), not 18990."
Write-Host "The OS is built in $($osState.targetDir) (reused next time). Started in $([math]::Round(((Get-Date) - $t0).TotalSeconds)) s."
Write-Host 'A change in app/shell/src shows in the window in under a second.'
if ($Watch) { Write-Host 'A change in rust/ rebuilds the app and the OS restarts it; the time is printed each time. This window stays open to watch (Ctrl+C here ends only the watching).' }
else { Write-Host 'A change in rust/ needs this command again with -Watch (it rebuilds the app and the OS restarts it).' }
Write-Host "To stop everything: scripts/bos-dev-app.ps1 -Stop -DevRoot `"$DevRoot`""
Write-Host "Logs: $logs"

if ($Watch) {
  $q = { (Get-ChildItem (Join-Path $repo 'rust') -Recurse -Include *.rs, Cargo.toml -File | Where-Object { $_.FullName -notmatch '\\(target|node_modules)\\' } | Measure-Object LastWriteTimeUtc -Maximum).Maximum }
  $last = & $q
  while ($true) {
    Start-Sleep -Milliseconds 700
    $now = & $q
    if ($now -gt $last) {
      $last = $now; $t = Get-Date
      try { Build-Lib; Write-Host ("library rebuilt in {0:n1} s; the OS restarts the app" -f ((Get-Date) - $t).TotalSeconds) } catch { Write-Host "bos-dev-app: $_" -ForegroundColor Red }
    }
  }
}
