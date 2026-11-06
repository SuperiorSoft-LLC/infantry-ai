# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
<#
.SYNOPSIS
  MIGRATE-1: move an Infantry AI state dir from the app you run today (a dist build) to the production MSI install.

.DESCRIPTION
  Both a dist build (agenthq-dist\builds\<stamp>\InfantryAI.exe) and the MSI start the SAME entry (AppMainKt) under the
  jpackage launcher, so both resolve their state dir with the same code (InstallProfile.stateDir):
    INFANTRY_INSTALL_STATE_DIR if set (and not ~\.infantry), else %LOCALAPPDATA%\SuperiorSoft\InfantryAI,
    unless that default sits inside the MSI's install dir (then %APPDATA%\SuperiorSoft\InfantryAI).
  With the default install dir (%LOCALAPPDATA%\InfantryAI) both apps use the same folder and there is nothing to copy:
  this script then says so, and -Apply only takes a snapshot backup before the new version first opens the database.

  Default is a dry run: it lists every file it would copy, with sizes and the target. -Apply copies. It never moves or
  deletes anything in the source. An existing target is first renamed to <target>.backup-<timestamp>.
  It refuses to apply while either app holds its state dir (the daemon's OS lock on daemon.lock, or the port in
  daemon.lock / daemon.port listening) or while something listens on the target port. It never looks processes up by
  image name and never stops one.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\migrate-to-prod.ps1            # dry run
  powershell -ExecutionPolicy Bypass -File scripts\migrate-to-prod.ps1 -Apply     # close both apps first
#>
[CmdletBinding()]
param(
    [string]$SourceStateDir,
    [string]$TargetStateDir,
    # The folder the MSI installs into (jpackage INSTALLDIR = %LOCALAPPDATA%\<packageName>); the installer lets you change it.
    [string]$InstallDir,
    [int]$TargetPort = 0,
    [switch]$Apply,
    [switch]$DryRun,
    [switch]$IncludeLogs,
    # Where INFANTRY_TOKEN_FILE / AGENTHQ_TOKEN_FILE are read and rewritten. Tests use Process.
    [ValidateSet('User', 'Process')][string]$EnvScope = 'User'
)

$ErrorActionPreference = 'Stop'

# --- resolution: mirrors superiorSoftLibrary/infantryUi/.../InstallProfile.kt -----------------------------------------

$DefaultInstallPort = 8797   # InstallProfile.DEFAULT_PORT
$DevPorts = @(8787, 8788)    # InstallProfile.DEV_PORTS

function Get-FullPath([string]$Path) {
    [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}

function Test-Inside([string]$Path, [string]$Dir) {
    if (-not $Path -or -not $Dir) { return $false }
    $p = (Get-FullPath $Path) + '\'
    $d = (Get-FullPath $Dir) + '\'
    return $p.StartsWith($d, [StringComparison]::OrdinalIgnoreCase)
}

function Resolve-InstallStateDir {
    param([hashtable]$Env, [string]$UserHome, [string]$InstallDir)
    function under([string]$base, [string]$fallback) {
        if (-not $base) { $base = Join-Path $UserHome $fallback }
        Join-Path (Join-Path $base 'SuperiorSoft') 'InfantryAI'
    }
    $local = under $Env['LOCALAPPDATA'] 'AppData\Local'
    $default = $local
    if ($InstallDir -and (Test-Inside $local $InstallDir)) {
        $roaming = under $Env['APPDATA'] 'AppData\Roaming'
        if (-not (Test-Inside $roaming $InstallDir)) { $default = $roaming }
    }
    $override = $Env['INFANTRY_INSTALL_STATE_DIR']
    if (-not $override) { return (Get-FullPath $default) }
    $override = Get-FullPath $override
    if ($override -ieq (Get-FullPath (Join-Path $UserHome '.infantry'))) { return (Get-FullPath $default) }
    if ($InstallDir -and (Test-Inside $override $InstallDir)) { return (Get-FullPath $default) }
    return $override
}

function Resolve-InstallPort([hashtable]$Env) {
    $raw = $Env['INFANTRY_INSTALL_PORT']
    $n = 0
    if ($raw -and [int]::TryParse($raw.Trim(), [ref]$n) -and $n -ge 1 -and $n -le 65535 -and $DevPorts -notcontains $n) { return $n }
    return $DefaultInstallPort
}

# --- what to copy ------------------------------------------------------------------------------------------------------

# Runtime files of the app that ran on the source: the daemon recreates them at start and they describe ITS processes.
$RuntimeTop = @('daemon.lock', 'daemon.port', 'infantry.db.holders', 'agenthq.db.holders', 'shims', 'process-ledger.json')
$RuntimeRel = @('plugin-data\Infantry_Processes\process-ledger.json')
$LogTop = @('logs', 'watch')

function Get-MigrationPlan {
    param([string]$Source, [switch]$IncludeLogs)
    $src = Get-FullPath $Source
    $copy = New-Object System.Collections.Generic.List[object]
    $skip = New-Object System.Collections.Generic.List[object]
    foreach ($f in Get-ChildItem -LiteralPath $src -Recurse -Force -File) {
        $rel = $f.FullName.Substring($src.Length + 1)
        $top = ($rel -split '[\\/]')[0]
        $why = $null
        if ($RuntimeTop -contains $top -or $RuntimeRel -contains $rel) { $why = 'runtime (recreated at start)' }
        elseif (-not $IncludeLogs -and $LogTop -contains $top) { $why = 'log (use -IncludeLogs to copy)' }
        $item = [pscustomobject]@{ Rel = $rel; Top = $top; Size = $f.Length; Why = $why }
        if ($why) { $skip.Add($item) } else { $copy.Add($item) }
    }
    [pscustomobject]@{ Source = $src; Copy = $copy; Skip = $skip }
}

function Format-Size([long]$b) {
    if ($b -ge 1MB) { return ('{0:N1} MB' -f ($b / 1MB)) }
    if ($b -ge 1KB) { return ('{0:N1} KB' -f ($b / 1KB)) }
    return "$b B"
}

# --- is an app on it? -------------------------------------------------------------------------------------------------

# StateDirLock.kt locks one byte at Long.MAX_VALUE - 1 of daemon.lock while the daemon lives (an OS lock, dropped on death).
function Test-StateLockHeld([string]$Dir) {
    $file = Join-Path $Dir 'daemon.lock'
    if (-not (Test-Path -LiteralPath $file)) { return $false }
    $fs = $null
    try {
        $fs = [IO.File]::Open($file, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::ReadWrite)
        $fs.Lock([long]::MaxValue - 1, 1)
        $fs.Unlock([long]::MaxValue - 1, 1)
        return $false
    } catch [System.IO.IOException] {
        return $true
    } catch {
        # Could not open it at all (e.g. denied): treat as held rather than copy a live database.
        return $true
    } finally {
        if ($fs) { $fs.Dispose() }
    }
}

# Something accepts connections on 127.0.0.1:[Port]: a connect probe, which works even where the listener table is
# hidden (some sandboxes show no LISTENING rows). Returns the owner's PID when the table shows it, else 'unknown'.
function Get-ListenerPid([int]$Port) {
    if ($Port -le 0 -or $Port -gt 65535) { return $null }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $ar = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $ar.AsyncWaitHandle.WaitOne(1500) -or -not $client.Connected) { return $null }
    } catch { return $null } finally { $client.Close() }
    try {
        $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($c -and $c.OwningProcess) { return [string]$c.OwningProcess }
    } catch { }
    $line = netstat -ano -p TCP 2>$null | Where-Object { $_ -match "^\s*TCP\s+\S+:$Port\s+\S+\s+LISTENING\s+(\d+)" } | Select-Object -First 1
    if ($line -and $line -match 'LISTENING\s+(\d+)' -and $Matches[1] -ne '0') { return $Matches[1] }
    return 'unknown'
}

function Get-RecordedPort([string]$Dir) {
    $lock = Join-Path $Dir 'daemon.lock'
    if (Test-Path -LiteralPath $lock) {
        $m = Select-String -LiteralPath $lock -Pattern '^port=(\d+)' | Select-Object -First 1
        if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    }
    $pf = Join-Path $Dir 'daemon.port'
    if (Test-Path -LiteralPath $pf) {
        $n = 0
        if ([int]::TryParse((Get-Content -LiteralPath $pf -Raw).Trim(), [ref]$n)) { return $n }
    }
    return 0
}

# Why [Dir] is in use, or $null. [ExtraPort]: a port the app for that dir will bind (the target's).
function Get-InUseReason([string]$Dir, [int]$ExtraPort = 0) {
    if (Test-Path -LiteralPath $Dir) {
        if (Test-StateLockHeld $Dir) {
            $holder = (Get-Content -LiteralPath (Join-Path $Dir 'daemon.lock') -ErrorAction SilentlyContinue) -join ', '
            return "a running app holds $Dir (daemon.lock: $holder)"
        }
        $p = Get-RecordedPort $Dir
        $owner = Get-ListenerPid $p
        if ($owner) { return "port $p recorded in $Dir is listening (PID $owner)" }
    }
    if ($ExtraPort -gt 0) {
        $owner = Get-ListenerPid $ExtraPort
        if ($owner) { return "port $ExtraPort (the target app's port) is listening (PID $owner)" }
    }
    return $null
}

# --- token ------------------------------------------------------------------------------------------------------------

# A *_TOKEN_FILE that points inside the source dir, and where the same file lands in the target.
function Get-TokenFileMoves([string]$Source, [string]$Target, [string]$Scope) {
    foreach ($name in 'INFANTRY_TOKEN_FILE', 'AGENTHQ_TOKEN_FILE') {
        $v = [Environment]::GetEnvironmentVariable($name, $Scope)
        if ($v -and (Test-Inside $v $Source)) {
            $rel = (Get-FullPath $v).Substring((Get-FullPath $Source).Length + 1)
            [pscustomobject]@{ Name = $name; From = $v; To = (Join-Path $Target $rel) }
        }
    }
}

function Get-SecretNames([string]$Dir) {
    $ks = Join-Path $Dir 'keystore.json'
    if (-not (Test-Path -LiteralPath $ks)) { return @() }
    try {
        $j = Get-Content -LiteralPath $ks -Raw | ConvertFrom-Json
        $names = @()
        foreach ($p in 'secrets', 'entries') {
            $v = $j.$p
            if ($v -is [array]) { $names += $v | ForEach-Object { if ($_ -is [string]) { $_ } elseif ($_.name) { $_.name } } }
            elseif ($v) { $names += $v.PSObject.Properties.Name }
        }
        return @($names | Where-Object { $_ })
    } catch { return @('<keystore.json unreadable>') }
}

# --- copy -------------------------------------------------------------------------------------------------------------

function Copy-Plan($Plan, [string]$Target) {
    foreach ($i in $Plan.Copy) {
        $to = Join-Path $Target $i.Rel
        $dir = Split-Path -Parent $to
        if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Copy-Item -LiteralPath (Join-Path $Plan.Source $i.Rel) -Destination $to -Force
    }
    # Copy integrity: every file present with its size; the databases also by hash.
    $bad = @()
    foreach ($i in $Plan.Copy) {
        $to = Join-Path $Target $i.Rel
        if (-not (Test-Path -LiteralPath $to) -or (Get-Item -LiteralPath $to).Length -ne $i.Size) { $bad += $i.Rel; continue }
        if ($i.Rel -match '\.db$') {
            $a = (Get-FileHash -LiteralPath (Join-Path $Plan.Source $i.Rel) -Algorithm SHA256).Hash
            $b = (Get-FileHash -LiteralPath $to -Algorithm SHA256).Hash
            if ($a -ne $b) { $bad += $i.Rel }
        }
    }
    return , $bad
}

function Invoke-Migration {
    param(
        [string]$SourceStateDir, [string]$TargetStateDir, [string]$InstallDir, [int]$TargetPort,
        [switch]$Apply, [switch]$IncludeLogs, [string]$EnvScope = 'User', [string]$Stamp = (Get-Date -Format 'yyyyMMdd-HHmmss')
    )
    $envMap = @{}
    foreach ($n in 'LOCALAPPDATA', 'APPDATA', 'INFANTRY_INSTALL_STATE_DIR', 'INFANTRY_INSTALL_PORT') { $envMap[$n] = [Environment]::GetEnvironmentVariable($n) }
    $userHome = [Environment]::GetFolderPath('UserProfile')
    if (-not $InstallDir) { $InstallDir = Join-Path $(if ($envMap['LOCALAPPDATA']) { $envMap['LOCALAPPDATA'] } else { Join-Path $userHome 'AppData\Local' }) 'InfantryAI' }
    # A dist build's install dir is its own build folder, which never contains the vendor folder: the plain default.
    if (-not $SourceStateDir) { $SourceStateDir = Resolve-InstallStateDir -Env $envMap -UserHome $userHome -InstallDir $null }
    if (-not $TargetStateDir) { $TargetStateDir = Resolve-InstallStateDir -Env $envMap -UserHome $userHome -InstallDir $InstallDir }
    if ($TargetPort -le 0) { $TargetPort = Resolve-InstallPort $envMap }
    $src = Get-FullPath $SourceStateDir
    $dst = Get-FullPath $TargetStateDir

    "Infantry AI state migration ($(if ($Apply) { 'APPLY' } else { 'DRY RUN: nothing is written' }))"
    "  source (the app you run today): $src"
    "  target (the MSI install)      : $dst"
    "  MSI install dir               : $InstallDir"
    "  target port                   : $TargetPort"
    if (-not (Test-Path -LiteralPath $src)) { "ERROR: the source state dir does not exist."; return 2 }
    if (Test-Inside $dst $InstallDir) { "ERROR: the target is inside the MSI install dir, which uninstall and upgrade delete."; return 2 }
    $same = ($src -ieq $dst)
    $plan = Get-MigrationPlan -Source $src -IncludeLogs:$IncludeLogs
    $total = ($plan.Copy | Measure-Object -Property Size -Sum).Sum
    if (-not $total) { $total = 0 }

    if ($same) {
        ""
        "SAME DIRECTORY: the MSI uses the state dir the current app already uses. There is NOTHING to migrate:"
        "close the current app, install the MSI and start it; it opens the same database, mail, roles, keystore and tokens."
        "-Apply only takes a snapshot backup of this dir first (the new version may upgrade the database on its first start)."
    }
    ""
    "Would copy $($plan.Copy.Count) files, $(Format-Size $total)$(if ($same) { ' into the snapshot' }):"
    $plan.Copy | Group-Object Top | Sort-Object Name | ForEach-Object {
        $sum = ($_.Group | Measure-Object -Property Size -Sum).Sum
        $label = if ($_.Count -eq 1 -and $_.Group[0].Rel -eq $_.Name) { $_.Name } else { "$($_.Name)\  ($($_.Count) files)" }
        '  {0,-52} {1,10}' -f $label, (Format-Size $sum)
    }
    if ($plan.Skip.Count) {
        "Not copied:"
        $plan.Skip | Group-Object Top, Why | Sort-Object Name | ForEach-Object {
            $label = if ($_.Count -eq 1) { $_.Group[0].Rel } else { "$($_.Group[0].Top)\  ($($_.Count) files)" }
            '  {0,-52} {1}' -f $label, $_.Group[0].Why
        }
    }
    $snapshot = "$src.backup-$Stamp"
    if ($same) {
        "Snapshot target: $snapshot"
    } elseif (Test-Path -LiteralPath $dst) {
        if (Get-ChildItem -LiteralPath $dst -Force | Select-Object -First 1) { "The target exists: it is first renamed to $dst.backup-$Stamp (nothing in it is deleted)." }
    }

    $tokenMoves = @(if (-not $same) { Get-TokenFileMoves $src $dst $EnvScope })
    ""
    "Token: owner.token, remote.token and network.properties are copied as they are, so the token value stays the same."
    foreach ($t in $tokenMoves) { "  $($t.Name) ($EnvScope) points into the source; -Apply repoints it: $($t.From) -> $($t.To)" }
    $secrets = @(Get-SecretNames $src)
    "Secrets: keystore.json is sealed by keystore.key.dpapi, which Windows DPAPI binds to this Windows user on this machine"
    "  (CurrentUser scope). It opens after the copy for the same user on the same PC. On another user or PC, re-enter: $(if ($secrets.Count) { $secrets -join ', ' } else { '(no stored secrets)' })."
    "Not in the state dir (unchanged, nothing to copy): each project's own .infantry\ folder and worktrees, harness logins"
    "  (~\.claude, opencode, pi), and the dev daemon's ~\.infantry. The shims and the sessions' infantry CLI path are rewritten"
    "  by the new app at every start. Agents running in the current app stop when you close it."
    $others = @()
    $vendor = Split-Path -Parent $src
    if ((Split-Path -Leaf $vendor) -ieq 'SuperiorSoft' -and (Test-Path -LiteralPath $vendor)) { $others += Get-ChildItem -LiteralPath $vendor -Directory | Where-Object { $_.FullName -ine $src -and $_.FullName -ine $dst } | ForEach-Object { $_.FullName } }
    foreach ($d in (Join-Path $userHome '.infantry'), (Join-Path $userHome '.agenthq')) { if (Test-Path -LiteralPath $d) { $others += $d } }
    if ($others.Count) { "Other state dirs seen (not migrated): $($others -join '; ')" }

    if (-not $Apply) {
        ""
        @(Get-InUseReason $src 0; Get-InUseReason $dst $TargetPort) | Where-Object { $_ } | Select-Object -Unique | ForEach-Object { "In use now: $_" }
        "Dry run only. Close both apps, then run again with -Apply."
        return 0
    }

    ""
    foreach ($check in @(@($src, 0), @($dst, $TargetPort))) {
        $why = Get-InUseReason $check[0] $check[1]
        if ($why) { "REFUSED: $why. Close the app (File > Quit, or its tray icon) and run again. Nothing was written."; return 1 }
    }
    if ($same) {
        $bad = Copy-Plan $plan $snapshot
        if ($bad.Count) { "FAILED: the snapshot copy differs for: $($bad -join ', ')"; return 3 }
        "Snapshot written: $snapshot ($($plan.Copy.Count) files). Nothing else to do: install the MSI and start it."
        return 0
    }
    if ((Test-Path -LiteralPath $dst) -and (Get-ChildItem -LiteralPath $dst -Force | Select-Object -First 1)) {
        Rename-Item -LiteralPath $dst -NewName (Split-Path -Leaf "$dst.backup-$Stamp")
        "Existing target backed up to $dst.backup-$Stamp"
    }
    New-Item -ItemType Directory -Path $dst -Force | Out-Null
    $bad = Copy-Plan $plan $dst
    if ($bad.Count) { "FAILED: the copy differs for: $($bad -join ', '). The source is untouched."; return 3 }
    foreach ($t in $tokenMoves) {
        [Environment]::SetEnvironmentVariable($t.Name, $t.To, $EnvScope)
        "$($t.Name) ($EnvScope) now $($t.To)"
    }
    "Copied $($plan.Copy.Count) files ($(Format-Size $total)) to $dst. The source is untouched."
    if ($dst -ine (Resolve-InstallStateDir -Env $envMap -UserHome $userHome -InstallDir $InstallDir)) {
        "The MSI only uses $dst if INFANTRY_INSTALL_STATE_DIR names it: setx INFANTRY_INSTALL_STATE_DIR `"$dst`""
    }
    return 0
}

if ($MyInvocation.InvocationName -ne '.') {
    if ($Apply -and $DryRun) { Write-Output 'ERROR: -Apply and -DryRun together'; exit 2 }
    $code = 0
    Invoke-Migration -SourceStateDir $SourceStateDir -TargetStateDir $TargetStateDir -InstallDir $InstallDir -TargetPort $TargetPort `
        -Apply:$Apply -IncludeLogs:$IncludeLogs -EnvScope $EnvScope | ForEach-Object { if ($_ -is [int]) { $code = $_ } else { $_ } }
    exit $code
}
