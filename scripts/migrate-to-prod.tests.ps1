# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# MIGRATE-1 scoped tests (no Gradle, temp dirs only): powershell -File scripts/migrate-to-prod.tests.ps1 ; exit 0 = all pass.
. "$PSScriptRoot\migrate-to-prod.ps1"
$fails = 0
function Check([string]$Name, [bool]$Ok) { if ($Ok) { "ok   $Name" } else { "FAIL $Name"; $script:fails++ } }
function Run([hashtable]$A) { $out = @(Invoke-Migration @A); [pscustomobject]@{ Code = ($out | Where-Object { $_ -is [int] } | Select-Object -Last 1); Text = ($out | Where-Object { $_ -isnot [int] }) -join "`n" } }
function Tree([string]$Dir) { Get-ChildItem -LiteralPath $Dir -Recurse -Force -File | ForEach-Object { "$($_.FullName.Substring($Dir.Length)) $((Get-FileHash -LiteralPath $_.FullName).Hash)" } }

$root = Join-Path ([IO.Path]::GetTempPath()) ("mig-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$src = Join-Path $root 'src'; $dst = Join-Path $root 'dst'
New-Item -ItemType Directory -Force -Path (Join-Path $src 'roles'), (Join-Path $src 'logs'), (Join-Path $src 'shims'), (Join-Path $src 'plugin-data\Infantry_Processes'), (Join-Path $src 'plugin-data\Infantry_Roles') | Out-Null
Set-Content -LiteralPath (Join-Path $src 'infantry.db') -Value ('x' * 4096) -NoNewline
Set-Content -LiteralPath (Join-Path $src 'owner.token') -Value 'tok' -NoNewline
Set-Content -LiteralPath (Join-Path $src 'roles\pm.md') -Value 'pm' -NoNewline
Set-Content -LiteralPath (Join-Path $src 'plugin-data\Infantry_Roles\r.json') -Value '{}' -NoNewline
Set-Content -LiteralPath (Join-Path $src 'plugin-data\Infantry_Processes\process-ledger.json') -Value '[]' -NoNewline
Set-Content -LiteralPath (Join-Path $src 'logs\infantry.log') -Value 'log' -NoNewline
Set-Content -LiteralPath (Join-Path $src 'shims\gradle.cmd') -Value 'old build path' -NoNewline
Set-Content -LiteralPath (Join-Path $src 'daemon.lock') -Value "pid=1`nport=1`n" -NoNewline
Set-Content -LiteralPath (Join-Path $src 'keystore.json') -Value '{"v":2,"entries":[],"secrets":["github"]}' -NoNewline
$before = Tree $src
$base = @{ SourceStateDir = $src; TargetStateDir = $dst; InstallDir = (Join-Path $root 'app'); TargetPort = 1; EnvScope = 'Process'; Stamp = 'T1' }

try {
    # Resolution mirrors InstallProfile.stateDir.
    $envA = @{ LOCALAPPDATA = 'C:\L'; APPDATA = 'C:\R' }
    Check 'default state dir' ((Resolve-InstallStateDir -Env $envA -UserHome 'C:\H' -InstallDir 'C:\L\InfantryAI') -eq 'C:\L\SuperiorSoft\InfantryAI')
    Check 'install dir over the vendor folder moves state to roaming' ((Resolve-InstallStateDir -Env $envA -UserHome 'C:\H' -InstallDir 'C:\L\SuperiorSoft') -eq 'C:\R\SuperiorSoft\InfantryAI')
    Check 'override wins' ((Resolve-InstallStateDir -Env @{ LOCALAPPDATA = 'C:\L'; INFANTRY_INSTALL_STATE_DIR = 'D:\S' } -UserHome 'C:\H' -InstallDir $null) -eq 'D:\S')
    Check 'override naming ~\.infantry is refused' ((Resolve-InstallStateDir -Env @{ LOCALAPPDATA = 'C:\L'; INFANTRY_INSTALL_STATE_DIR = 'C:\H\.infantry' } -UserHome 'C:\H' -InstallDir $null) -eq 'C:\L\SuperiorSoft\InfantryAI')
    Check 'dev port is not an install port' ((Resolve-InstallPort @{ INFANTRY_INSTALL_PORT = '8787' }) -eq 8797)
    Check 'install port override' ((Resolve-InstallPort @{ INFANTRY_INSTALL_PORT = '8812' }) -eq 8812)

    # Plan: runtime and logs out, data in.
    $plan = Get-MigrationPlan -Source $src
    $rels = @($plan.Copy | ForEach-Object { $_.Rel })
    Check 'plan copies db, token, roles, plugin data' (($rels -contains 'infantry.db') -and ($rels -contains 'owner.token') -and ($rels -contains 'roles\pm.md') -and ($rels -contains 'plugin-data\Infantry_Roles\r.json'))
    Check 'plan skips lock, shims, process ledger, logs' (-not (($rels -contains 'daemon.lock') -or ($rels -contains 'shims\gradle.cmd') -or ($rels -contains 'plugin-data\Infantry_Processes\process-ledger.json') -or ($rels -contains 'logs\infantry.log')))
    Check '-IncludeLogs copies logs' (@((Get-MigrationPlan -Source $src -IncludeLogs).Copy | Where-Object { $_.Rel -eq 'logs\infantry.log' }).Count -eq 1)

    # Dry run writes nothing.
    $r = Run $base
    Check 'dry run exit 0' ($r.Code -eq 0)
    Check 'dry run lists the target and a file' ($r.Text -match [regex]::Escape($dst) -and $r.Text -match 'infantry\.db')
    Check 'dry run names the secrets to re-enter elsewhere' ($r.Text -match 'github')
    Check 'dry run creates no target' (-not (Test-Path -LiteralPath $dst))

    # Same dir: nothing to migrate.
    $r = Run ($base + @{}) ; $same = $base.Clone(); $same.TargetStateDir = $src
    $r = Run $same
    Check 'same dir says nothing to migrate' ($r.Text -match 'NOTHING to migrate')

    # Refused while the source lock is held (the daemon's byte lock, as StateDirLock takes it).
    $fs = [IO.File]::Open((Join-Path $src 'daemon.lock'), 'Open', 'ReadWrite', 'ReadWrite')
    $fs.Lock([long]::MaxValue - 1, 1)
    try {
        Check 'lock detected' (Test-StateLockHeld $src)
        $a = $base.Clone(); $a.Apply = $true
        $r = Run $a
        Check 'apply refused while the source app runs' ($r.Code -eq 1 -and $r.Text -match 'REFUSED' -and -not (Test-Path -LiteralPath $dst))
    } finally { $fs.Unlock([long]::MaxValue - 1, 1); $fs.Dispose() }
    Check 'lock released is not held' (-not (Test-StateLockHeld $src))

    # Refused while something listens on the target port.
    $l = New-Object System.Net.Sockets.TcpListener ([Net.IPAddress]::Loopback), 0
    $l.Start()
    try {
        $a = $base.Clone(); $a.Apply = $true; $a.TargetPort = $l.LocalEndpoint.Port
        $r = Run $a
        Check 'apply refused while the target port listens' ($r.Code -eq 1 -and $r.Text -match 'target app''s port')
    } finally { $l.Stop() }

    # Apply: existing target is backed up, data copied, source untouched, token file repointed.
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    Set-Content -LiteralPath (Join-Path $dst 'infantry.db') -Value 'fresh msi db' -NoNewline
    [Environment]::SetEnvironmentVariable('INFANTRY_TOKEN_FILE', (Join-Path $src 'owner.token'), 'Process')
    $a = $base.Clone(); $a.Apply = $true
    $r = Run $a
    Check 'apply exit 0' ($r.Code -eq 0)
    Check 'old target kept as backup' ((Get-Content -LiteralPath "$dst.backup-T1\infantry.db" -Raw) -eq 'fresh msi db')
    Check 'db copied byte for byte' ((Get-FileHash (Join-Path $src 'infantry.db')).Hash -eq (Get-FileHash (Join-Path $dst 'infantry.db')).Hash)
    Check 'roles and plugin data copied' ((Test-Path (Join-Path $dst 'roles\pm.md')) -and (Test-Path (Join-Path $dst 'plugin-data\Infantry_Roles\r.json')))
    Check 'runtime files not copied' (-not ((Test-Path (Join-Path $dst 'daemon.lock')) -or (Test-Path (Join-Path $dst 'shims'))))
    Check 'source untouched' ((Compare-Object @($before) @(Tree $src)) -eq $null)
    Check 'INFANTRY_TOKEN_FILE repointed into the target' ([Environment]::GetEnvironmentVariable('INFANTRY_TOKEN_FILE', 'Process') -eq (Join-Path $dst 'owner.token'))
    Check 'mentions the setx the MSI needs for a non-default target' ($r.Text -match 'INFANTRY_INSTALL_STATE_DIR')

    # Same dir apply: a snapshot, nothing else.
    $s = $same.Clone(); $s.Apply = $true; $s.Stamp = 'S1'
    $r = Run $s
    Check 'same dir apply writes a snapshot' ($r.Code -eq 0 -and (Test-Path -LiteralPath "$src.backup-S1\infantry.db"))
    Check 'target inside install dir refused' ((Run ($base + @{})).Code -eq 0 -and (& { $b = $base.Clone(); $b.TargetStateDir = (Join-Path $root 'app\state'); (Run $b).Code }) -eq 2)
} finally {
    [Environment]::SetEnvironmentVariable('INFANTRY_TOKEN_FILE', $null, 'Process')
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
if ($fails) { "$fails failed"; exit 1 } else { 'all passed'; exit 0 }
