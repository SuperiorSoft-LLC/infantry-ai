# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-PERF-1: the SAME agent scenario on the Kotlin app and the Tauri app, at 1, 5 and 10 agents.
#
#   pwsh scripts/perf/compare.ps1                         # both apps, 1/5/10 agents
#   pwsh scripts/perf/compare.ps1 -Apps tauri -Agents 1   # one quick run
#   pwsh scripts/perf/compare.ps1 -Apps tauri-daemon      # RUST-DAEMON-2: Tauri on its infantry-daemon sidecar
#
# Each run: a fresh temp root (state dir + an empty throwaway project folder), the app started with every
# INFANTRY_* / AGENTHQ_* / CLAUDE* variable removed, then:
#   startup  : launch -> main window handle; launch -> ready (Tauri: page script ran; Kotlin: daemon /health answers)
#   baseline : ~8 s with no agents, after 15 s of settling
#   agents-up: N sessions spawned, each answers "Reply with the word ok"
#   idle     : -IdleSec seconds, every agent idle
#   stream   : every agent writes ~300 words at once
# A sampler walks the app's process tree about once a second (working set, private bytes, CPU time per role:
# app/daemon, webview, mcp bridges, agent CLIs, consoles). The Tauri scenario runs in-page (perf-scenario.ts); the
# Kotlin one is driven over the daemon's WebSocket (kotlin-driver.mjs). Kotlin runs as a SEPARATE instance on a 189xx
# port with its own temp state: never the owner's app. Everything started is killed by PID at the end of each run.
param(
    [ValidateSet('kotlin', 'tauri', 'tauri-daemon')][string[]]$Apps = @('kotlin', 'tauri'),
    [int[]]$Agents = @(1, 5, 10),
    [int]$IdleSec = 20,
    [string]$Model = 'haiku',
    [int]$Port = 18911,
    [string]$KotlinDir = 'C:\Users\antho\agenthq-dist\builds\20261117-181728-6afaed5e',
    [string]$TauriExe = "$PSScriptRoot\..\..\app\src-tauri\target\release\infantry-tauri.exe",
    [string]$DaemonExe = "$PSScriptRoot\..\..\rust\target\release\infantry-daemon.exe",
    [int]$DaemonPort = 18964,
    [string]$Out = "$PSScriptRoot\results.json"
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib.ps1"
foreach ($p in @($Port, $DaemonPort)) { if ($p -in 8787, 8788, 8797, 8798 -or $p -lt 18900 -or $p -gt 18999) { throw "test ports are 189xx only" } }
$TauriExe = (Resolve-Path $TauriExe).Path
$driver = "$PSScriptRoot\kotlin-driver.mjs"

function Read-Events([string]$file) {
    if (-not (Test-Path $file)) { return @() }
    return @(Get-Content $file | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
}

function Test-Health([int]$p) {
    try { (Invoke-WebRequest "http://127.0.0.1:$p/health" -TimeoutSec 1 -UseBasicParsing).StatusCode -eq 200 } catch { $false }
}

function Get-Window([System.Diagnostics.Process]$proc) {
    try { $proc.Refresh(); return $proc.MainWindowHandle -ne [IntPtr]::Zero } catch { return $false }
}

function Measure-Run([string]$app, [int]$n) {
    $root = Join-Path ([IO.Path]::GetTempPath()) ("infantry-perf-$app-$n-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    $work = Join-Path $root 'work'; $state = Join-Path $root 'state'; $events = Join-Path $root 'events.jsonl'
    New-Item -ItemType Directory -Force $work, $state | Out-Null
    Write-Host "[$app n=$n] temp root $root"
    if ($app -eq 'kotlin') {
        if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "port $Port is taken" }
        $exe = Join-Path $KotlinDir 'InfantryAI.exe'
        $extra = @{ INFANTRY_INSTALL_PORT = $Port; INFANTRY_INSTALL_STATE_DIR = $state }
    } else {
        $exe = $TauriExe
        $extra = @{
            INFANTRY_UI_LOCAL = '1'; INFANTRY_STATE_DIR = $state; INFANTRY_PERF_OUT = $events
            INFANTRY_PERF_SCENARIO = "agents=$n;idle=$IdleSec;model=$Model;cwd=$work;delay=25"
        }
        if ($app -eq 'tauri-daemon') {
            # The app starts infantry-daemon as its child (the sidecar), so the tree walk counts it ('daemon' role).
            $extra.INFANTRY_UI_LOCAL = '0'; $extra.INFANTRY_UI_DAEMON_PORT = "$DaemonPort"
            $extra.INFANTRY_DAEMON_BIN = (Resolve-Path $DaemonExe).Path
        }
    }
    $launchedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $proc = Start-Clean -Exe $exe -Cwd $work -Extra $extra
    $rootPid = $proc.Id
    $res = [ordered]@{ app = $app; agents = $n; pid = $rootPid; samples = @(); baseline = @(); events = @() }
    $nodeProc = $null
    try {
        $windowMs = $null; $readyMs = $null
        while ($sw.ElapsedMilliseconds -lt 90000 -and ($null -eq $windowMs -or $null -eq $readyMs)) {
            if ($null -eq $windowMs -and (Get-Window $proc)) { $windowMs = $sw.ElapsedMilliseconds }
            if ($null -eq $readyMs) {
                if ($app -eq 'kotlin') { if (Test-Health $Port) { $readyMs = $sw.ElapsedMilliseconds } }
                else {
                    # The page's own clock: when its first render ran (not when this loop saw the file).
                    $r = Read-Events $events | Where-Object event -eq 'ui-ready' | Select-Object -First 1
                    if ($r) { $readyMs = [long]$r.t - $launchedAt }
                }
            }
            if ($proc.HasExited) { throw "the app exited during startup (code $($proc.ExitCode))" }
            Start-Sleep -Milliseconds 50
        }
        $res.windowMs = $windowMs; $res.readyMs = $readyMs
        Write-Host "[$app n=$n] window $windowMs ms, ready $readyMs ms"
        if ($app -eq 'kotlin') {
            $base = @()
            Start-Sleep -Seconds 15   # let startup settle, as the Tauri scenario's delay does
            for ($i = 0; $i -lt 10; $i++) { $base += Get-Sample -Root $rootPid; Start-Sleep -Milliseconds 700 }
            $res.baseline = $base
            $nodeProc = Start-Process node -ArgumentList @("`"$driver`"", "ws://127.0.0.1:$Port/ws", $n, $IdleSec, $Model, "`"$events`"", "`"$work`"") -PassThru -NoNewWindow -RedirectStandardError (Join-Path $root 'driver.err')
        }
        $deadline = (Get-Date).AddMinutes(12)
        $samples = [System.Collections.Generic.List[object]]::new()
        while ((Get-Date) -lt $deadline) {
            $samples.Add((Get-Sample -Root $rootPid))
            $ev = Read-Events $events
            if ($ev | Where-Object event -eq 'scenario-done') { break }
            if ($proc.HasExited) { throw "the app exited mid-run" }
            if ($nodeProc -and $nodeProc.HasExited -and -not ($ev | Where-Object event -eq 'scenario-done')) {
                throw "the driver failed: $(Get-Content (Join-Path $root 'driver.err') -Raw)"
            }
            Start-Sleep -Milliseconds 700
        }
        $res.samples = $samples
        $res.events = Read-Events $events
    } finally {
        if ($nodeProc -and -not $nodeProc.HasExited) { Stop-Process -Id $nodeProc.Id -Force -ErrorAction SilentlyContinue }
        $seen = @($res.samples | ForEach-Object { $_.pids }) + @($res.baseline | ForEach-Object { $_.pids }) | Select-Object -Unique
        Stop-Tree -Root $rootPid
        Start-Sleep -Seconds 3
        # Anything the tree walk saw that outlived the kill (an orphaned agent CLI) is killed and counted.
        $left = @($seen | Where-Object { $_ -and (Get-Process -Id $_ -ErrorAction SilentlyContinue) })
        foreach ($p in $left) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
        $res.leftAlive = $left.Count
        if (Test-Path (Join-Path $root 'driver.err')) { $res.driverErr = (Get-Content (Join-Path $root 'driver.err') -Raw) }
        Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
    }
    return $res
}

function Get-Window2($s, $a, $b) { @($s | Where-Object { $_.t -ge $a -and $_.t -le $b }) }

function Summarize($run) {
    $ev = @{}; foreach ($e in $run.events) { $ev[$e.event] = $e }
    $roles = 'app', 'daemon', 'webview', 'mcp', 'agents', 'console', 'other'
    $s = $run.samples
    $idle = if ($ev['idle-start'] -and $ev['idle-end']) { Get-Window2 $s $ev['idle-start'].t $ev['idle-end'].t } else { @() }
    $stream = if ($ev['stream-start'] -and $ev['stream-end']) { Get-Window2 $s $ev['stream-start'].t $ev['stream-end'].t } else { @() }
    # Baseline (no agents): Kotlin samples before the driver starts; Tauri samples in the 9 s before its scenario spawns (delay=25).
    $base = if ($run.baseline.Count) { $run.baseline } elseif ($ev['spawn-start']) { @($s | Where-Object { $_.t -lt $ev['spawn-start'].t -and $_.t -ge $ev['spawn-start'].t - 9000 }) } else { @() }
    $mem = [ordered]@{}; $peak = [ordered]@{}; $baseMem = [ordered]@{}; $names = [ordered]@{}
    $idleRoles = @($idle | ForEach-Object { Get-RoleMem $_ })
    $streamRoles = @($stream | ForEach-Object { Get-RoleMem $_ })
    $baseRoles = @($base | ForEach-Object { Get-RoleMem $_ })
    foreach ($r in $roles) {
        $vals = @($idleRoles | ForEach-Object { $_[$r] } | Where-Object { $_ })
        if ($vals.Count) {
            $mem[$r] = [ordered]@{
                wsMb = [math]::Round((($vals | ForEach-Object { $_.ws } | Measure-Object -Average).Average) / 1MB, 1)
                privMb = [math]::Round((($vals | ForEach-Object { $_.priv } | Measure-Object -Average).Average) / 1MB, 1)
                procs = ($vals | Select-Object -Last 1).n
            }
            $names[$r] = @($vals | ForEach-Object { $_.names.Keys } | Select-Object -Unique)
        }
        $pv = @($streamRoles | ForEach-Object { $_[$r] } | Where-Object { $_ })
        if ($pv.Count) { $peak[$r] = [math]::Round((($pv | ForEach-Object { $_.ws } | Measure-Object -Maximum).Maximum) / 1MB, 1) }
        $bv = @($baseRoles | ForEach-Object { $_[$r] } | Where-Object { $_ })
        if ($bv.Count) {
            $baseMem[$r] = [ordered]@{
                wsMb = [math]::Round((($bv | ForEach-Object { $_.ws } | Measure-Object -Average).Average) / 1MB, 1)
                privMb = [math]::Round((($bv | ForEach-Object { $_.priv } | Measure-Object -Average).Average) / 1MB, 1)
                names = @($bv | ForEach-Object { $_.names.Keys } | Select-Object -Unique)
            }
        }
    }
    $cpuIdle = if ($idle.Count -ge 2) { Get-RoleCpu $idle[0] $idle[-1] } else { @{} }
    $cpuStream = if ($stream.Count -ge 2) { Get-RoleCpu $stream[0] $stream[-1] } else { @{} }
    $cpuBase = if ($base.Count -ge 2) { Get-RoleCpu $base[0] $base[-1] } else { @{} }
    $se = $ev['stream-end']
    return [ordered]@{
        app = $run.app; agents = $run.agents; windowMs = $run.windowMs; readyMs = $run.readyMs
        agentsUp = $ev['agents-up']; streamOk = if ($se) { $se.ok } else { $false }
        streamSeconds = if ($ev['stream-start'] -and $se) { [math]::Round(($se.t - $ev['stream-start'].t) / 1000) } else { $null }
        fps = if ($se) { $se.fps } else { $null }; worstFps = if ($se) { $se.worstFps } else { $null }
        p99FrameMs = if ($se) { $se.p99FrameMs } else { $null }; longFrames = if ($se) { $se.longFrames } else { $null }
        baselineMem = $baseMem; cpuBaselinePct = $cpuBase; idleMem = $mem; idleNames = $names; streamPeakWsMb = $peak; cpuIdlePct = $cpuIdle; cpuStreamPct = $cpuStream
        leftAlive = $run.leftAlive; driverErr = $run.driverErr
    }
}

$all = @()
foreach ($n in $Agents) {
    foreach ($app in $Apps) {
        $run = Measure-Run $app $n
        $sum = Summarize $run
        $all += $sum
        $sum | ConvertTo-Json -Depth 6 | Write-Host
        [ordered]@{ partial = $true; runs = $all } | ConvertTo-Json -Depth 7 | Set-Content -Encoding utf8 $Out
        Start-Sleep -Seconds 5
    }
}

# Sizes
$tauriDir = Split-Path $TauriExe
$installer = Get-ChildItem (Join-Path $tauriDir 'bundle\nsis') -Filter *.exe -ErrorAction SilentlyContinue | Select-Object -First 1
$sizes = [ordered]@{
    tauriExeMb = [math]::Round((Get-Item $TauriExe).Length / 1MB, 1)
    tauriInstallerMb = if ($installer) { [math]::Round($installer.Length / 1MB, 1) } else { $null }
    kotlinLauncherMb = [math]::Round((Get-Item (Join-Path $KotlinDir 'InfantryAI.exe')).Length / 1MB, 2)
    kotlinInstallDirMb = [math]::Round(((Get-ChildItem $KotlinDir -Recurse -File | Measure-Object Length -Sum).Sum) / 1MB, 1)
}
$final = [ordered]@{ when = (Get-Date).ToString('s'); model = $Model; idleSec = $IdleSec; sizes = $sizes; runs = $all }
$final | ConvertTo-Json -Depth 7 | Set-Content -Encoding utf8 $Out
Write-Host "results: $Out"
