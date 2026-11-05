# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# SHELL-SHOW-1: one step to SEE the desktop shell of this checkout with no plugins (or with only the plugins you name).
#
#   pwsh scripts/show-empty-shell.ps1                         # the empty shell: zero plugins
#   pwsh scripts/show-empty-shell.ps1 -Mode root              # the shell + the plugin manager (Infantry_Settings) only
#   pwsh scripts/show-empty-shell.ps1 -Mode root -Plugins Infantry_Settings,Infantry_Notes
#   pwsh scripts/show-empty-shell.ps1 -Keep                   # keep the temp folder after the window closes
#   pwsh scripts/show-empty-shell.ps1 -NoBuild                # reuse the last build (skips Gradle)
#
# Isolation: everything lives in one new temp folder (%TEMP%\infantry-shell-show\<stamp>): the app dir (app.json,
# plugins/), and a temp user.home, so the shell's own bundled daemon keeps its state under <temp>\home\.infantry\apps\...
# It binds 127.0.0.1 on an OS-assigned port (AppDaemonProfile), never 8787/8788/8797/8798, and never the owner's state.
# INFANTRY_* / AGENTHQ_* / CLAUDE* are removed from the child's environment. The script starts the JVM itself and, when
# it ends (window closed or Ctrl+C), stops only THAT process by PID. A drop-in .ihq to try is put next to the app dir.
[CmdletBinding()]
param(
  [ValidateSet('empty', 'root')] [string]$Mode = 'empty',
  [string[]]$Plugins = @('Infantry_Settings'),
  [switch]$Keep,
  [switch]$NoBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $repo $(if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'gradlew.bat' } else { 'gradlew' })
$launchDir = Join-Path $repo 'superiorSoftLibrary/infantryUi/build/shell/launch'

# The child must not see the owner's daemon settings or an agent's identity (test-daemon env rules).
foreach ($v in [Environment]::GetEnvironmentVariables().Keys) {
  if ($v -like 'INFANTRY_*' -or $v -like 'AGENTHQ_*' -or $v -like 'CLAUDE*') { Remove-Item -LiteralPath "Env:$v" }
}

if ($Mode -eq 'root') { $Plugins = @($Plugins | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
if (-not $NoBuild) {
  $tasks = @(':superiorSoftLibrary:infantryUi:shellLaunchFiles', ':plugins:_template:ihq')
  if ($Mode -eq 'root') { foreach ($p in $Plugins) { $tasks += ":plugins:official:${p}:jvmJar"; $tasks += ":daemon-plugin-official-${p}:jar" } }
  Write-Host "[shell-show] building: $($tasks -join ' ')"
  & $gradlew -p $repo @tasks --console=plain -q
  if ($LASTEXITCODE -ne 0) { throw "gradle failed ($LASTEXITCODE)" }
}
$java = (Get-Content -LiteralPath (Join-Path $launchDir 'java.txt') -Raw).Trim()
$argFile = Join-Path $launchDir 'shell.args'

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$root = Join-Path ([IO.Path]::GetTempPath()) "infantry-shell-show\$stamp"
$appDir = Join-Path $root 'app'
$home2 = Join-Path $root 'home'
$pluginsRoot = Join-Path $appDir 'plugins'
foreach ($d in 'core', 'official', 'community') { New-Item -ItemType Directory -Force (Join-Path $pluginsRoot $d) | Out-Null }
New-Item -ItemType Directory -Force $home2 | Out-Null
$appId = "show.shell.$Mode.$stamp"
$title = if ($Mode -eq 'empty') { 'Infantry Shell (no plugins)' } else { "Infantry Shell + $($Plugins -join ', ')" }
@{ appId = $appId; name = $title; windowTitle = $title; daemon = @{ mode = 'connect-or-bundle'; url = $null; seedProject = 'Shell demo' } } |
  ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $appDir 'app.json') -Encoding utf8
# The starter .ihq is unsigned (a dev build), so this temp install accepts unsigned packages into the community tier.
'{ "allowUnsigned": true }' | Set-Content -LiteralPath (Join-Path $pluginsRoot 'install.json') -Encoding utf8

if ($Mode -eq 'root') {
  foreach ($p in $Plugins) {
    $src = Join-Path $repo "plugins/official/$p"
    if (-not (Test-Path -LiteralPath (Join-Path $src 'module.json'))) { throw "no plugin plugins/official/$p" }
    $dst = Join-Path $pluginsRoot "official/$p"
    New-Item -ItemType Directory -Force $dst | Out-Null
    Copy-Item -LiteralPath (Join-Path $src 'module.json') -Destination $dst
    $m = Get-Content -LiteralPath (Join-Path $src 'module.json') -Raw | ConvertFrom-Json
    foreach ($jar in @($m.jar, $m.daemon.jar) | Where-Object { $_ }) {
      $from = Join-Path $src $jar
      if (-not (Test-Path -LiteralPath $from)) { throw "missing $from (build it, or drop -NoBuild)" }
      $to = Join-Path $dst $jar
      New-Item -ItemType Directory -Force (Split-Path -Parent $to) | Out-Null
      Copy-Item -LiteralPath $from -Destination $to
    }
  }
}
$ihq = Join-Path $repo 'plugins/_template/build/ihq/Example_Starter.ihq'
if (Test-Path -LiteralPath $ihq) { Copy-Item -LiteralPath $ihq -Destination $root }

$log = Join-Path $root 'shell.log'
$jvm = @("-Duser.home=$home2", '-Dinfantry.plugins.devTrust=true', "@$argFile", '--app', $appDir)
Write-Host "[shell-show] mode=$Mode  folder=$root"
Write-Host "[shell-show] log=$log"
if (Test-Path -LiteralPath (Join-Path $root 'Example_Starter.ihq')) { Write-Host "[shell-show] a plugin to try: drop $root\Example_Starter.ihq on the drop box (or use its file picker)" }
$proc = Start-Process -FilePath $java -ArgumentList ($jvm | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) `
  -WorkingDirectory $root -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
Write-Host "[shell-show] started PID $($proc.Id); close the window (or Ctrl+C here) to end"
try {
  $proc.WaitForExit()
  Write-Host "[shell-show] the shell exited with $($proc.ExitCode)"
} finally {
  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force; Write-Host "[shell-show] stopped PID $($proc.Id)" }
  if (-not $Keep) { Start-Sleep -Milliseconds 500; Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
  else { Write-Host "[shell-show] kept $root" }
}
