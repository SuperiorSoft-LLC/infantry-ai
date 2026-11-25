# SPDX-License-Identifier: MPL-2.0
# BOS-31: develop Infantry AI inside a BattalionOS DEVELOPER build, with live reload and no package, signature, catalog or install.
#
#   scripts/bos-dev-app.ps1 -Os <BattalionOS checkout> [-Watch]
#
# One command: builds the app library in dev form (cargo build -p infantry-distribution), lays the app folder (app.json, lib/, the page
# entry), starts the page's dev server (app/shell, Vite) on a free port of this PC's loopback only, and starts the BattalionOS dev build
# (scripts/bos-dev.ps1 of that checkout) with the folder registered (`--dev-app <folder> --dev-url <the dev server>`).
#   - A change in app/shell/src is seen in the OS window by the page's own hot reload; the OS is not touched.
#   - -Watch: a change in rust/ rebuilds the library only and copies it into the folder; the OS stops the app the normal way and starts the
#     new one (same OS process, other apps untouched). The OS is not rebuilt.
# Dev mode has its own data folder for the OS and the app (never %LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust). Loopback only.
param(
  [Parameter(Mandatory)][string]$Os,
  [string]$Folder = (Join-Path (Split-Path $PSScriptRoot -Parent) 'dist-bosdev'),
  [string]$DevRoot = (Join-Path $env:TEMP 'bos-dev-ia'),
  [string]$OsTargetDir = '',
  [switch]$Watch
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$lib = 'infantry_distribution.dll'

function Build-Lib {
  Push-Location (Join-Path $repo 'rust')
  try { & cargo build -p infantry-distribution 2>&1 | Select-Object -Last 3; if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' } } finally { Pop-Location }
  Push-Location (Join-Path $repo 'rust'); try { $target = (& cargo metadata --format-version 1 --no-deps | ConvertFrom-Json).target_directory } finally { Pop-Location }
  $built = Join-Path $target "debug\$lib"
  if (-not (Test-Path $built)) { throw "library not found: $built" }
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
Build-Lib

# The page's dev server, loopback only.
$l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0); $l.Start(); $port = $l.LocalEndpoint.Port; $l.Stop()
# The app's own daemon port: a free loopback port, never the default one of the installed app (18990).
$l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0); $l.Start(); $daemonPort = $l.LocalEndpoint.Port; $l.Stop()
$psi = [Diagnostics.ProcessStartInfo]::new('cmd.exe', "/c pnpm exec vite --host 127.0.0.1 --port $port --strictPort")
$psi.WorkingDirectory = Join-Path $repo 'app\shell'; $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
$psi.Environment['INFANTRY_UI_PORT'] = "$port"
foreach ($k in @($psi.Environment.Keys)) { if ($k -match '^(BATTALION_|AGENTHQ_)' -or ($k -match '^INFANTRY_' -and $k -ne 'INFANTRY_UI_PORT')) { $psi.Environment.Remove($k) | Out-Null } }
$vite = [Diagnostics.Process]::Start($psi)
for ($i = 0; $i -lt 150; $i++) { try { $c = [Net.Sockets.TcpClient]::new('127.0.0.1', $port); $c.Close(); break } catch { Start-Sleep -Milliseconds 200 } }
New-Item -ItemType Directory -Force $DevRoot | Out-Null
@{ vite = $vite.Id; port = $port; folder = $Folder } | ConvertTo-Json | Set-Content (Join-Path $DevRoot 'ia-pids.json')
Write-Host "Infantry AI page dev server: http://127.0.0.1:$port (pid $($vite.Id)); app folder $Folder"

# The OS, with the folder registered.
$savedTarget = $env:CARGO_TARGET_DIR
if ($OsTargetDir) { $env:CARGO_TARGET_DIR = $OsTargetDir }  # the OS builds in its own target dir, not the app's
try { & pwsh -NoProfile -File (Join-Path $Os 'scripts' | Join-Path -ChildPath 'bos-dev.ps1') -App $Folder -AppUrl "http://127.0.0.1:$port" -DevRoot $DevRoot -AppEnv "INFANTRY_PORT=$daemonPort" -Open infantry-ai } finally { $env:CARGO_TARGET_DIR = $savedTarget }

if ($Watch) {
  $q = { (Get-ChildItem (Join-Path $repo 'rust') -Recurse -Include *.rs, Cargo.toml -File | Where-Object { $_.FullName -notmatch '\\(target|node_modules)\\' } | Measure-Object LastWriteTimeUtc -Maximum).Maximum }
  $last = & $q
  while ($true) {
    Start-Sleep -Milliseconds 700
    $now = & $q
    if ($now -gt $last) {
      $last = $now; $t = Get-Date
      try { Build-Lib; Write-Host ("library rebuilt in {0:n1} s; the OS restarts the app" -f ((Get-Date) - $t).TotalSeconds) } catch { Write-Host $_ }
    }
  }
}
