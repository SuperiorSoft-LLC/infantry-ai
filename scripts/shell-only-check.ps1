# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# SHELL-ONLY-1: proves the OS boot chain on this PC. The `shell-only` flavour is the core daemon plus the installer
# plugin and nothing else; the script builds it, boots it on a throwaway state dir and port, installs the rest from a
# local plugin repo through the installer, and checks that the result matches the bundled app (every plugin
# pre-installed, no special code path).
#
#   pwsh scripts/shell-only-check.ps1                       # stage the plugins from this checkout, run every check
#   pwsh scripts/shell-only-check.ps1 -PluginRepo D:\repo   # a local clone / folder laid out <repo>/official/<Module>/module.json
#   pwsh scripts/shell-only-check.ps1 -SkipBuild            # reuse the last build of the daemon
#   pwsh scripts/shell-only-check.ps1 -Serve shell-only     # no checks: leave the flavour running with a Vite shell on it, to look at
#   pwsh scripts/shell-only-check.ps1 -Serve bundled        #   (prints the URLs; Ctrl+C / commands_stop ends it)
#   pwsh scripts/shell-only-check.ps1 -Keep                 # keep the temp folder (logs, states) afterwards
#
# What it checks (each line prints PASS or FAIL; the exit code is 0 only when all pass):
#   shell-only: 1 the daemon starts with an empty plugins folder and installs ONLY the installer from its bundle
#               2 boot_progress says bootstrap mode (the installer is the window); the installer's screen is served
#               3 the plugin repo is configured (plugins.sources) and offers its plugins to the installer
#               4 installing the app (APPS-ROOT-1: the UI plugin, whose manifest lists its members) installs its whole set and ends bootstrap mode
#               5 the board works: create a ticket, then list it
#   bundled:    6 a daemon with every plugin pre-installed is not in bootstrap mode, and its board works
#   match:      7 the installed shell-only build and the bundled one have the same plugins and the same agent tools
#   footprint:  on-disk size and idle memory of both flavours (printed; the numbers are the evidence)
# Isolation: a free loopback port (never 8787/8788/8797/8798/18990), a new state dir under %TEMP%\infantry-shell-only\<stamp>,
# INFANTRY_* / AGENTHQ_* removed from the environment first. It never touches the owner's daemon or state dir.
[CmdletBinding()]
param(
  [string]$PluginRepo = '',
  [string]$Profile = 'owner-test',
  [string]$PluginProfile = 'release',
  [int]$IdleSeconds = 20,
  [ValidateSet('', 'shell-only', 'bundled')] [string]$Serve = '',
  [switch]$SkipBuild,
  [switch]$Keep
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$ownerPorts = 8787, 8788, 8797, 8798, 18990
$installer = 'Infantry_Installer'
$uiPlugin = 'Infantry_Ui'

foreach ($v in [Environment]::GetEnvironmentVariables().Keys) {
  if ($v -like 'INFANTRY_*' -or $v -like 'AGENTHQ_*') { Remove-Item -LiteralPath "Env:$v" }
}
. (Join-Path $repoRoot 'scripts/rust-build-env.ps1')

$results = [System.Collections.Generic.List[object]]::new()
function Check([string]$Name, [scriptblock]$Body) {
  try {
    $detail = & $Body
    $results.Add([pscustomobject]@{ Name = $Name; Pass = $true; Detail = "$detail" }) | Out-Null
    Write-Host ("PASS  {0}  {1}" -f $Name, $detail)
  } catch {
    $results.Add([pscustomobject]@{ Name = $Name; Pass = $false; Detail = $_.Exception.Message }) | Out-Null
    Write-Host ("FAIL  {0}  {1}" -f $Name, $_.Exception.Message) -ForegroundColor Red
  }
}
function Get-FreePort {
  while ($true) {
    $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $l.Start(); $p = $l.LocalEndpoint.Port; $l.Stop()
    if ($ownerPorts -notcontains $p) { return $p }
  }
}
function Get-DirBytes([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) { return 0 }
  $m = Get-ChildItem -LiteralPath $Path -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum
  [long]$m.Sum
}
function MB([double]$Bytes) { [math]::Round($Bytes / 1MB, 1) }

# One request/response on /ws (this daemon runs without a token on loopback, and then every client is the owner).
function Invoke-Frame([int]$Port, [string]$Type, [string]$Data = '', [int]$TimeoutSec = 30) {
  $ws = [System.Net.WebSockets.ClientWebSocket]::new()
  $cts = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSec))
  try {
    $ws.ConnectAsync([Uri]"ws://127.0.0.1:$Port/ws", $cts.Token).GetAwaiter().GetResult()
    $id = [guid]::NewGuid().ToString('N')
    $bytes = [Text.Encoding]::UTF8.GetBytes((@{ type = $Type; data = $Data; requestId = $id } | ConvertTo-Json -Compress))
    $ws.SendAsync([ArraySegment[byte]]::new($bytes), [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cts.Token).GetAwaiter().GetResult()
    $buf = [byte[]]::new(65536)
    while ($true) {
      $ms = [IO.MemoryStream]::new()
      do {
        $r = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $cts.Token).GetAwaiter().GetResult()
        if ($r.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) { throw "the daemon closed the socket while waiting for the answer to $Type" }
        $ms.Write($buf, 0, $r.Count)
      } until ($r.EndOfMessage)
      $f = [Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json
      if ($f.requestId -eq $id) { return $f }
    }
  } catch [System.OperationCanceledException] {
    throw "no answer to $Type within $TimeoutSec s"
  } finally { $ws.Dispose() }
}
function Get-Json([object]$Frame) { if ($Frame.data) { $Frame.data | ConvertFrom-Json } else { $null } }
function Invoke-Admin([int]$Port, [string[]]$AdminArgs) {
  $f = Invoke-Frame $Port 'plugin_admin' (@{ args = $AdminArgs } | ConvertTo-Json -Compress) 120
  $j = Get-Json $f
  if ($null -eq $j -or $null -eq $j.code) { throw "plugin_admin $($AdminArgs -join ' ') answered a $($f.type) frame: $($f.data)" }
  $j
}
function Get-PluginIds([int]$Port) { @((Get-Json (Invoke-Frame $Port 'plugins_op' '{"op":"list"}')) | ForEach-Object { $_.id } | Sort-Object) }
function Get-ToolNames([int]$Port) { @((Get-Json (Invoke-Frame $Port 'plugin_tools' '')) | ForEach-Object { $_.name } | Sort-Object) }
# One agent tool of a plugin that is not the installer: the answer proves its daemon half is loaded and runs.
function Invoke-HealthTool([int]$Port) {
  $arg = @{ name = 'infantry_health'; arguments = @{}; client = @{} } | ConvertTo-Json -Compress
  $f = Invoke-Frame $Port 'plugin_tool_call' $arg 60
  $j = Get-Json $f
  if ($f.type -ne 'plugin_tool_result' -or $j.isError) { throw "infantry_health answered $($f.type): $($f.data)" }
  "$($j.text)".Length
}
function Get-Boot([int]$Port) { Get-Json (Invoke-Frame $Port 'boot_progress' '{}') }

# ---- a daemon process of one flavour ---------------------------------------------------------------------------
$script:daemons = @()
function Start-Flavour([string]$PluginsDir, [string]$BundleDir, [string]$State, [int]$Port, [string]$LogName) {
  New-Item -ItemType Directory -Force $PluginsDir, $State | Out-Null
  $env:INFANTRY_PLUGINS_DIR = $PluginsDir
  if ($BundleDir) { $env:INFANTRY_BUNDLED_PLUGINS_DIR = $BundleDir } else { $env:INFANTRY_BUNDLED_PLUGINS_DIR = Join-Path $work 'no-bundle' }
  $p = Start-Process $daemonExe -ArgumentList '--port', $Port, '--state-dir', "`"$State`"" -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $work "$LogName.out.log") -RedirectStandardError (Join-Path $work "$LogName.err.log")
  $script:daemons += $p
  $deadline = (Get-Date).AddSeconds(60)
  while ($true) {
    if ($p.HasExited) { throw "the daemon exited ($($p.ExitCode)) before /health answered; see $work\$LogName.err.log" }
    try { Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 2 | Out-Null; break } catch { }
    if ((Get-Date) -gt $deadline) { throw "the daemon did not answer /health within 60 s (see $work\$LogName.err.log)" }
    Start-Sleep -Milliseconds 300
  }
  $p
}
function Stop-Daemon($p) {
  if ($p -and -not $p.HasExited) { & taskkill.exe /PID $p.Id /T /F | Out-Null; $p.WaitForExit(10000) | Out-Null }
}
function Get-Memory($p, [int]$Settle) {
  Start-Sleep -Seconds $Settle
  $p.Refresh()
  [pscustomobject]@{ WorkingSetMB = MB $p.WorkingSet64; PrivateMB = MB $p.PrivateMemorySize64; Threads = $p.Threads.Count }
}

# ---- build + stage ---------------------------------------------------------------------------------------------
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$work = Join-Path ([IO.Path]::GetTempPath()) "infantry-shell-only\$stamp"
New-Item -ItemType Directory -Force $work | Out-Null
$daemonExe = Join-Path $env:CARGO_TARGET_DIR "$Profile\infantry-daemon.exe"
$vite = $null
try {
  if (-not $SkipBuild) {
    Write-Host "[shell-only] building infantry-daemon ($Profile) into $env:CARGO_TARGET_DIR"
    cargo build --profile $Profile --manifest-path (Join-Path $repoRoot 'rust\Cargo.toml') -p infantry-distribution 2>&1 | Select-Object -Last 5 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed ($LASTEXITCODE)" }
  }
  if (-not (Test-Path $daemonExe)) { throw "no daemon at $daemonExe (drop -SkipBuild)" }

  if (-not $PluginRepo) {
    $PluginRepo = Join-Path $work 'plugin-repo'
    $appDir = Join-Path $repoRoot 'app'
    if (-not (Test-Path (Join-Path $appDir 'node_modules'))) {
      Write-Host '[shell-only] pnpm install --frozen-lockfile (the plugins'' web UI halves need it)'
      pnpm -C $appDir install --frozen-lockfile 2>&1 | Select-Object -Last 3 | ForEach-Object { Write-Host "  $_" }
      if ($LASTEXITCODE -ne 0) { throw "pnpm install failed ($LASTEXITCODE)" }
    }
    # Git Bash, never C:\Windows\system32\bash.exe (that is WSL).
    $gitRoot = Split-Path (Get-Command git).Source -Parent
    while ($gitRoot -and -not (Test-Path (Join-Path $gitRoot 'bin\bash.exe'))) { $gitRoot = Split-Path $gitRoot -Parent }
    $gitBash = Join-Path $gitRoot 'bin\bash.exe'
    if (-not (Test-Path $gitBash)) { throw 'Git Bash not found (needed for rust/plugins/stage.sh)' }
    Write-Host "[shell-only] staging the official plugins ($PluginProfile) into the plugin repo folder $PluginRepo"
    & $gitBash (Join-Path $repoRoot 'rust/plugins/stage.sh') $PluginRepo $PluginProfile 2>&1 | Select-Object -Last 4 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw 'plugin staging failed' }
  }
  $repoMods = @(Get-ChildItem -LiteralPath (Join-Path $PluginRepo 'official') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'module.json') } | ForEach-Object Name | Sort-Object)
  if ($repoMods -notcontains $installer) { throw "the plugin repo $PluginRepo has no official/$installer" }
  if ($repoMods -notcontains $uiPlugin) { throw "the plugin repo $PluginRepo has no official/$uiPlugin" }
  Write-Host "[shell-only] plugin repo offers $($repoMods.Count) modules: $($repoMods -join ', ')"

  # ---- the two flavours on disk ----------------------------------------------------------------------------------
  # shell-only: an EMPTY plugins folder, and a bundle folder that holds the installer and nothing else (what would ship).
  $shellPlugins = Join-Path $work 'shell/plugins'
  $shellBundle = Join-Path $work 'shell/bundle'
  New-Item -ItemType Directory -Force (Join-Path $shellBundle 'official') | Out-Null
  Copy-Item -LiteralPath (Join-Path $PluginRepo "official\$installer") -Destination (Join-Path $shellBundle 'official') -Recurse
  # bundled: every plugin pre-installed in the plugins folder (a bundled build is the same chain with the plugins in place).
  $bundledPlugins = Join-Path $work 'bundled/plugins'
  New-Item -ItemType Directory -Force $bundledPlugins | Out-Null
  Copy-Item -LiteralPath (Join-Path $PluginRepo 'official') -Destination $bundledPlugins -Recurse
  $exeMB = MB (Get-Item $daemonExe).Length

  if ($Serve) {
    $port = Get-FreePort
    $uiPort = Get-FreePort
    if ($Serve -eq 'shell-only') {
      $state = Join-Path $work 'shell/state'
      $d = Start-Flavour $shellPlugins $shellBundle $state $port 'serve'
      Invoke-Frame $port 'settings_set' (@{ key = 'plugins.sources'; value = @($PluginRepo) } | ConvertTo-Json -Compress) | Out-Null
      Stop-Daemon $d
      $d = Start-Flavour $shellPlugins $shellBundle $state $port 'serve2'
    } else {
      $d = Start-Flavour $bundledPlugins '' (Join-Path $work 'bundled/state') $port 'serve'
    }
    $env:INFANTRY_PORT = "$port"; $env:INFANTRY_UI_PORT = "$uiPort"
    Write-Host "[shell-only] $Serve daemon on 127.0.0.1:$port (pid $($d.Id)), state under $work"
    Write-Host "[shell-only] PREVIEW URL: http://localhost:$uiPort/   (daemon :$port)"
    pnpm -C (Join-Path $repoRoot 'app') dev
    return
  }

  # ---- SHELL-ONLY ------------------------------------------------------------------------------------------------
  Write-Host '[shell-only] === flavour 1: shell-only (core + installer) ==='
  $port = Get-FreePort
  $state = Join-Path $work 'shell/state'
  $script:shellDaemon = $null
  Check '1 shell-only boots with an empty plugins folder and installs only the installer from its bundle' {
    $script:shellDaemon = Start-Flavour $shellPlugins $shellBundle $state $port 'shell'
    $ids = Get-PluginIds $port
    if (($ids -join ',') -ne $installer) { throw "installed plugins are [$($ids -join ', ')], expected only $installer" }
    "installed: $($ids -join ', ')"
  }
  Check '2 bootstrap mode is reported and the installer screen is served' {
    $b = Get-Boot $port
    if (-not $b.ready) { throw "boot_progress not ready: $($b | ConvertTo-Json -Compress)" }
    if ($b.bootstrap.plugin -ne $installer -or $b.bootstrap.active -ne $true) { throw "bootstrap = $($b.bootstrap | ConvertTo-Json -Compress)" }
    $code = (Invoke-WebRequest "http://127.0.0.1:$port/ui-plugins/$installer/ui/dist/index.js" -UseBasicParsing -SkipHttpErrorCheck).StatusCode
    if ($code -ne 200) { throw "the installer's UI bundle answered $code" }
    "bootstrap active, installer UI served (200)"
  }
  $memBoot = $null
  $memBoot = Get-Memory $script:shellDaemon $IdleSeconds
  $shellDiskBoot = Get-DirBytes $shellPlugins
  Write-Host ("[shell-only] idle after boot: working set {0} MB, private {1} MB, plugins on disk {2} MB" -f $memBoot.WorkingSetMB, $memBoot.PrivateMB, (MB $shellDiskBoot))

  Check '3 the plugin repo is configured and the installer offers its plugins' {
    # Sources are read at start: set the setting, then restart the same daemon on the same state (bootstrap mode must survive).
    Invoke-Frame $port 'settings_set' (@{ key = 'plugins.sources'; value = @($PluginRepo) } | ConvertTo-Json -Compress) | Out-Null
    Stop-Daemon $script:shellDaemon
    $script:shellDaemon = Start-Flavour $shellPlugins $shellBundle $state $port 'shell-restart'
    $b = Get-Boot $port
    if ($b.bootstrap.active -ne $true) { throw "bootstrap mode did not survive the restart: $($b.bootstrap | ConvertTo-Json -Compress)" }
    $a = Invoke-Admin $port @('available')
    if ($a.code -ne 0) { throw "available failed: $($a.err)" }
    $rows = @($a.out | ConvertFrom-Json)
    $offered = @($rows | ForEach-Object { $_.id } | Sort-Object)
    $missing = @($repoMods | Where-Object { $offered -notcontains $_ })
    if ($missing.Count) { throw "the installer does not offer: $($missing -join ', ')" }
    "$($offered.Count) modules offered, $(@($rows | Where-Object installed).Count) installed"
  }
  Check '4a installing the app installs its core members and ends bootstrap mode (the normal app)' {
    $r = Invoke-Admin $port @('install', $uiPlugin)
    if ($r.code -ne 0) { throw "install $uiPlugin failed: $($r.err)" }
    $b = Get-Boot $port
    if ($b.bootstrap.active -ne $false) { throw "still in bootstrap mode after installing $uiPlugin : $($b.bootstrap | ConvertTo-Json -Compress)" }
    "$($r.out); bootstrap.active = false"
  }
  Check '4b the app brought every plugin of the set (live, no restart, nothing else installed by hand)' {
    $ids = Get-PluginIds $port
    $missing = @($repoMods | Where-Object { $ids -notcontains $_ })
    if ($missing.Count) { throw "not installed by the app: $($missing -join ', ')" }
    "$($ids.Count) plugins installed"
  }
  $shellIds = @(); $shellTools = @()
  Check '5 the board works: create a ticket, then list it' {
    $title = "shell-only check $stamp"
    $c = Invoke-Frame $port 'ticket_save' (@{ title = $title } | ConvertTo-Json -Compress)
    $t = Get-Json $c
    if ($c.type -ne 'ticket' -or -not $t.id) { throw "ticket_save answered a $($c.type) frame: $($c.data)" }
    $l = Get-Json (Invoke-Frame $port 'ticket_list' '')
    if (-not (@($l) | Where-Object { $_.id -eq $t.id -and $_.title -eq $title })) { throw "ticket $($t.id) is not in ticket_list" }
    "created $($t.id), listed ($(@($l).Count) ticket(s))"
  }
  try { $shellIds = Get-PluginIds $port; $shellTools = Get-ToolNames $port } catch { }
  Check '5b a plugin installed live runs its agent tool (infantry_health)' { "answered, $(Invoke-HealthTool $port) chars" }
  $memFull = Get-Memory $script:shellDaemon $IdleSeconds
  $shellDiskFull = Get-DirBytes $shellPlugins
  Write-Host ("[shell-only] idle after installing everything: working set {0} MB, private {1} MB, plugins on disk {2} MB" -f $memFull.WorkingSetMB, $memFull.PrivateMB, (MB $shellDiskFull))
  # The same install after a restart: the way a bundled build starts (everything on disk when the daemon boots).
  Stop-Daemon $script:shellDaemon
  $script:shellDaemon = Start-Flavour $shellPlugins $shellBundle $state $port 'shell-installed-restart'
  $memRestart = Get-Memory $script:shellDaemon $IdleSeconds
  Check '5c the installed build restarts straight into the app (no bootstrap mode) with its ticket kept' {
    $b = Get-Boot $port
    if ($b.bootstrap.active -eq $true) { throw 'bootstrap mode came back after a restart' }
    $l = Get-Json (Invoke-Frame $port 'ticket_list' '')
    if (-not (@($l) | Where-Object { $_.title -eq "shell-only check $stamp" })) { throw 'the ticket is gone after the restart' }
    "bootstrap.active = false, ticket kept, idle working set $($memRestart.WorkingSetMB) MB"
  }
  Stop-Daemon $script:shellDaemon

  # ---- BUNDLED ---------------------------------------------------------------------------------------------------
  Write-Host '[shell-only] === flavour 2: bundled (every plugin pre-installed) ==='
  $bport = Get-FreePort
  $script:bundledDaemon = $null
  $bundledIds = @(); $bundledTools = @()
  Check '6 the bundled build boots straight into the app (no bootstrap mode) and its board works' {
    $script:bundledDaemon = Start-Flavour $bundledPlugins '' (Join-Path $work 'bundled/state') $bport 'bundled'
    $b = Get-Boot $bport
    if (-not $b.ready) { throw "boot_progress not ready" }
    if ($b.bootstrap.active -eq $true) { throw "bundled build is in bootstrap mode" }
    $script:bundledIds = Get-PluginIds $bport
    $script:bundledTools = Get-ToolNames $bport
    Invoke-HealthTool $bport | Out-Null
    $c = Invoke-Frame $bport 'ticket_save' (@{ title = "bundled check $stamp" } | ConvertTo-Json -Compress)
    $t = Get-Json $c
    if ($c.type -ne 'ticket' -or -not $t.id) { throw "ticket_save answered a $($c.type) frame: $($c.data)" }
    $l = Get-Json (Invoke-Frame $bport 'ticket_list' '')
    if (-not (@($l) | Where-Object { $_.id -eq $t.id })) { throw "ticket $($t.id) is not in ticket_list" }
    "bootstrap.active = false; $($script:bundledIds.Count) plugins; ticket $($t.id) created and listed"
  }
  $memBundled = $null
  if ($script:bundledDaemon) {
    $memBundled = Get-Memory $script:bundledDaemon $IdleSeconds
    Write-Host ("[shell-only] bundled idle: working set {0} MB, private {1} MB, plugins on disk {2} MB" -f $memBundled.WorkingSetMB, $memBundled.PrivateMB, (MB (Get-DirBytes $bundledPlugins)))
  }
  Stop-Daemon $script:bundledDaemon

  Check '7 the installed shell-only build matches the bundled build (same plugins, same agent tools)' {
    $bi = @($script:bundledIds); $bt = @($script:bundledTools)
    $dI = Compare-Object $shellIds $bi
    $dT = Compare-Object $shellTools $bt
    if ($dI) { throw "plugins differ: $(($dI | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }) -join ', ') ( <= shell-only, => bundled )" }
    if ($dT) { throw "tools differ: $(($dT | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }) -join ', ')" }
    "$($shellIds.Count) plugins and $($shellTools.Count) tools on both sides"
  }

  # ---- footprint -------------------------------------------------------------------------------------------------
  Write-Host ''
  Write-Host ('FOOTPRINT (this PC; daemon profile {0}, plugins {1})' -f $Profile, $PluginProfile)
  $rows = @(
    [pscustomobject]@{ Flavour = 'shell-only, at boot (installer only)'; Daemon_MB = $exeMB; Plugins_MB = (MB $shellDiskBoot); Total_MB = [math]::Round($exeMB + (MB $shellDiskBoot), 1); Idle_WorkingSet_MB = $memBoot.WorkingSetMB; Idle_Private_MB = $memBoot.PrivateMB }
    [pscustomobject]@{ Flavour = 'shell-only, everything installed'; Daemon_MB = $exeMB; Plugins_MB = (MB $shellDiskFull); Total_MB = [math]::Round($exeMB + (MB $shellDiskFull), 1); Idle_WorkingSet_MB = $memFull.WorkingSetMB; Idle_Private_MB = $memFull.PrivateMB }
    [pscustomobject]@{ Flavour = 'shell-only, installed, after restart'; Daemon_MB = $exeMB; Plugins_MB = (MB $shellDiskFull); Total_MB = [math]::Round($exeMB + (MB $shellDiskFull), 1); Idle_WorkingSet_MB = $memRestart.WorkingSetMB; Idle_Private_MB = $memRestart.PrivateMB }
    [pscustomobject]@{ Flavour = 'bundled (all pre-installed)'; Daemon_MB = $exeMB; Plugins_MB = (MB (Get-DirBytes $bundledPlugins)); Total_MB = [math]::Round($exeMB + (MB (Get-DirBytes $bundledPlugins)), 1); Idle_WorkingSet_MB = $memBundled.WorkingSetMB; Idle_Private_MB = $memBundled.PrivateMB }
  )
  $rows | Format-Table -AutoSize | Out-String | Write-Host
  Write-Host "Idle = $IdleSeconds s after the daemon answered /health, nothing connected. The shell-only plugin folder also holds the installer's bundle copy; the plugin repo itself ($((MB (Get-DirBytes $PluginRepo))) MB) is a source, not part of either build."
} finally {
  foreach ($p in $script:daemons) { Stop-Daemon $p }
  if (-not $Keep -and (Test-Path $work)) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
  elseif (Test-Path $work) { Write-Host "[shell-only] kept: $work" }
}

$failed = @($results | Where-Object { -not $_.Pass })
Write-Host ''
Write-Host ("RESULT: {0} of {1} checks passed" -f ($results.Count - $failed.Count), $results.Count)
if ($failed.Count) { exit 1 }
