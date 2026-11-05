# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
<#
.SYNOPSIS
  UPDATER-1: local production emulation of the Infantry_Updater flow, end to end, on a throwaway state dir and port.

.DESCRIPTION
  Proves, against a real GitHub release of -Repo (private or public):
    1. token     whether a read token is available (prints only "set" / "none", never the value)
    2. release   the latest release and its assets (MSI, server archives, APK, SHA256SUMS)
    3. server    an OLDER headless build (built with -OldVersion) finds, downloads, verifies and installs the newer archive
                 (`infantry update --yes`), with its own state dir
    4. msi       the Windows MSI is downloaded and its SHA-256 checked, then only EXTRACTED (`msiexec /a`): a real install
                 would upgrade the owner's own Infantry AI (same upgrade code), so this never runs `msiexec /i`
    5. apk       a test daemon (Infantry_Updater's daemon half) answers the phone's `GET /update/client`; the APK is
                 downloaded from the token-free URL it gives and checked; with -Adb it is installed on the attached device
    6. public    the same check with updater.source=public: no token is sent (a private repo then answers "not found")

  Safety: refuses the owner's ports (8787 8788 8797 8798) and any state dir outside %TEMP%; strips every inherited
  INFANTRY_* / AGENTHQ_* / CLAUDE* variable from the children; never prints a token or a signed download URL.

  The token: a fine-grained, read-only (Contents: read), this-repo-only GitHub token. Put it in YOUR user environment:
      [Environment]::SetEnvironmentVariable('INFANTRY_UPDATE_TOKEN', '<token>', 'User')
  (or in the test state dir's keystore as secret `update-token`). This script reads it from the process or user env and
  hands it to the children only; it is never written to a file.

  Launch-day switch (repo made public): set updater.source=public (the default) and delete the token:
      [Environment]::SetEnvironmentVariable('INFANTRY_UPDATE_TOKEN', $null, 'User')

.EXAMPLE
  pwsh scripts/updater-e2e.ps1                       # all steps, private source
  pwsh scripts/updater-e2e.ps1 -Steps token,release  # just the read-only checks
  pwsh scripts/updater-e2e.ps1 -Source public        # the launch-day (tokenless) path
#>
param(
    # The release repo (owner/repo) and API come from the same config point the updater reads (UpdateConfig): env
    # INFANTRY_UPDATE_REPO / INFANTRY_UPDATE_API (process, else user env). Nothing here names the repo.
    [string]$Repo = $(if ($env:INFANTRY_UPDATE_REPO) { $env:INFANTRY_UPDATE_REPO } else { [Environment]::GetEnvironmentVariable('INFANTRY_UPDATE_REPO', 'User') }),
    [ValidateSet('private', 'public')] [string]$Source = 'private',
    [string[]]$Steps = @('token', 'release', 'server', 'msi', 'apk', 'public'),
    # An MSI-valid version (MAJOR >= 1) older than the release under test.
    [string]$OldVersion = '1.0.0',
    [int]$Port = 18797,
    [string]$Root = (Join-Path ([IO.Path]::GetTempPath()) 'infantry-updater-e2e'),
    [switch]$Adb,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
# `pwsh -File` passes `-Steps a,b` as one string.
$Steps = @($Steps | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$repoRoot = Split-Path -Parent $PSScriptRoot
$api = $(if ($env:INFANTRY_UPDATE_API) { $env:INFANTRY_UPDATE_API } elseif ([Environment]::GetEnvironmentVariable('INFANTRY_UPDATE_API', 'User')) { [Environment]::GetEnvironmentVariable('INFANTRY_UPDATE_API', 'User') } else { 'https://api.github.com' })
if (-not $Repo -or $Repo -notmatch '^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$') { throw "no release repo: set INFANTRY_UPDATE_REPO=owner/repo (or pass -Repo)" }

if ($Port -in 8787, 8788, 8797, 8798) { throw "port $Port belongs to the owner's daemon or UI; pick another" }
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$Root = [IO.Path]::GetFullPath($Root)
if (-not $Root.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "state root $Root is not under $tempRoot" }
New-Item -ItemType Directory -Force $Root | Out-Null
$state = Join-Path $Root 'state'
New-Item -ItemType Directory -Force $state | Out-Null

function Say([string]$step, [string]$text) { Write-Host ("[{0,-7}] {1}" -f $step, $text) }

# The token, from this process or the user's environment. Never printed.
$token = $env:INFANTRY_UPDATE_TOKEN
if (-not $token) { $token = [Environment]::GetEnvironmentVariable('INFANTRY_UPDATE_TOKEN', 'User') }
$tokenState = if ($token) { 'set' } else { 'none' }

function GitHubHeaders([bool]$withToken) {
    $h = @{ Accept = 'application/vnd.github+json'; 'User-Agent' = 'infantry-updater-e2e' }
    if ($withToken -and $token) { $h.Authorization = "Bearer $token" }
    $h
}

# A clean environment for a child: nothing inherited that points at the owner's daemon or a parent agent.
function ChildEnv([hashtable]$extra) {
    $envs = @{}
    foreach ($e in [Environment]::GetEnvironmentVariables().GetEnumerator()) {
        if ($e.Key -match '^(INFANTRY_|AGENTHQ_|CLAUDE)') { continue }
        $envs[$e.Key] = $e.Value
    }
    $envs.INFANTRY_STATE_DIR = $state
    $envs.INFANTRY_PORT = "$Port"
    $envs.INFANTRY_URL = "ws://127.0.0.1:$Port/ws"
    $envs.INFANTRY_UPDATE_REPO = $Repo
    $envs.INFANTRY_UPDATE_SOURCE = $Source
    if ($Source -eq 'private' -and $token) { $envs.INFANTRY_UPDATE_TOKEN = $token }
    foreach ($k in $extra.Keys) { $envs[$k] = $extra[$k] }
    $envs
}

function RunChild([string]$exe, [string[]]$argv, [hashtable]$extra = @{}, [switch]$Wait) {
    $psi = [Diagnostics.ProcessStartInfo]::new($exe)
    foreach ($a in $argv) { $psi.ArgumentList.Add($a) }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $Wait
    $psi.RedirectStandardError = $Wait
    $psi.Environment.Clear()
    $envs = ChildEnv $extra
    foreach ($k in $envs.Keys) { $psi.Environment[$k] = [string]$envs[$k] }
    $p = [Diagnostics.Process]::Start($psi)
    if (-not $Wait) { return $p }
    $out = $p.StandardOutput.ReadToEnd() + $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    if ($token) { $out = $out.Replace($token, '<token>') }
    [pscustomobject]@{ Code = $p.ExitCode; Out = $out }
}

function Sha256([string]$file) { (Get-FileHash -Algorithm SHA256 $file).Hash.ToLowerInvariant() }

# Downloads a release asset: the API URL with Accept: octet-stream; GitHub answers a redirect to a signed host, which is
# followed WITHOUT the token.
function DownloadAsset($asset, [string]$dest, [bool]$withToken) {
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromMinutes(30)
    try {
        $req = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, $asset.url)
        $req.Headers.Add('Accept', 'application/octet-stream')
        $req.Headers.Add('User-Agent', 'infantry-updater-e2e')
        if ($withToken -and $token) { $req.Headers.Add('Authorization', "Bearer $token") }
        $resp = $client.SendAsync($req).GetAwaiter().GetResult()
        $code = [int]$resp.StatusCode
        if ($code -in 301, 302, 303, 307, 308) {
            # The signed URL of another host: followed with no token.
            $plain = [Net.Http.HttpClient]::new()
            $plain.Timeout = [TimeSpan]::FromMinutes(30)
            try {
                $plain.DefaultRequestHeaders.Add('User-Agent', 'infantry-updater-e2e')
                $in = $plain.GetStreamAsync($resp.Headers.Location).GetAwaiter().GetResult()
                $out = [IO.File]::Create($dest)
                try { $in.CopyTo($out) } finally { $out.Dispose(); $in.Dispose() }
            } finally { $plain.Dispose() }
            return
        } elseif ($code -eq 200) {
            $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        } else { throw "asset $($asset.name): HTTP $code" }
        [IO.File]::WriteAllBytes($dest, $bytes)
    } finally { $client.Dispose() }
}

$release = $null
function LatestRelease([bool]$withToken) {
    try {
        Invoke-RestMethod -Uri "$api/repos/$Repo/releases/latest" -Headers (GitHubHeaders $withToken)
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        if ($code -eq 404) { return 'not-found' }
        throw "GitHub answered HTTP $code"
    }
}

function Sums($rel, [bool]$withToken) {
    $a = $rel.assets | Where-Object name -eq 'SHA256SUMS' | Select-Object -First 1
    if (-not $a) { throw "release $($rel.tag_name) has no SHA256SUMS" }
    $f = Join-Path $Root 'SHA256SUMS'
    DownloadAsset $a $f $withToken
    $map = @{}
    foreach ($line in Get-Content $f) { if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') { $map[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant() } }
    $map
}

$private = $Source -eq 'private'
# The unpacked older server archive (its first run installs itself under server/versions/ and writes server/infantry).
$serverHome = Join-Path $Root "server/InfantryAI-server-$OldVersion-windows-x64"
$blocked = $false

if ('token' -in $Steps) {
    Say token "source=$Source token=$tokenState (env INFANTRY_UPDATE_TOKEN, process or user)"
    if ($private -and -not $token) {
        Say token 'BLOCKED: put a fine-grained read-only token (Contents: read, this repo only) in your user env:'
        Say token "  [Environment]::SetEnvironmentVariable('INFANTRY_UPDATE_TOKEN', '<token>', 'User')   then open a new shell"
        $blocked = $true
    }
}

if ('release' -in $Steps -and -not $blocked) {
    $release = LatestRelease $private
    if ($release -eq 'not-found') {
        Say release "no published release of $Repo is visible (private repo without a token, or no release yet). Publish one: git tag v<x.y.z>; git push origin v<x.y.z>"
        $blocked = $true
    } else {
        Say release "latest $($release.tag_name) ($($release.assets.Count) assets)"
        $release.assets | ForEach-Object { Say release ("  {0}  {1:N0} bytes" -f $_.name, $_.size) }
    }
}

if ('server' -in $Steps -and -not $blocked) {
    $old = $serverHome
    if (-not $SkipBuild) {
        Say server "building the older headless server archive (headlessDist, infantryVersion=$OldVersion)"
        Push-Location $repoRoot
        # -PallowAnyBranch: a test build from a ticket branch (the packaging guard otherwise wants a clean release branch).
        try { & ./gradlew.bat :superiorSoftLibrary:infantry:headlessDist "-PinfantryVersion=$OldVersion" -PallowAnyBranch=true --console=plain -q | Select-Object -Last 5 } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { throw "the older headless build failed (gradle exit $LASTEXITCODE)" }
        $zip = Get-ChildItem (Join-Path $repoRoot 'superiorSoftLibrary/infantry/build/distributions') -Filter "InfantryAI-server-$OldVersion-windows-*.zip" | Select-Object -First 1
        if (-not $zip) { throw "no InfantryAI-server-$OldVersion-windows-*.zip was built" }
        $serverRoot = Join-Path $Root 'server'
        if (Test-Path $serverRoot) { Remove-Item -Recurse -Force $serverRoot }
        Expand-Archive $zip.FullName $serverRoot
    }
    $cmd = Join-Path $old 'bin/infantry.cmd'
    $r = RunChild $cmd @('update', '--check-only') -Wait
    Say server "check-only exit $($r.Code) (0 up to date, 10 an update exists, 3 cannot self-update, 1 failed)"
    ($r.Out -split "`n" | Select-Object -Last 8) | ForEach-Object { Say server "  $_" }
    if ($r.Code -eq 10) {
        $r = RunChild $cmd @('update', '--yes') -Wait
        Say server "update --yes exit $($r.Code)"
        ($r.Out -split "`n" | Select-Object -Last 8) | ForEach-Object { Say server "  $_" }
        $versions = Join-Path (Split-Path -Parent $old) 'versions'
        if (Test-Path $versions) { Say server "installed versions: $((Get-ChildItem $versions -Directory).Name -join ', ')" }
    }
}

if ('msi' -in $Steps -and -not $blocked) {
    $msi = $release.assets | Where-Object name -match '^(InfantryAI|AgentHQ)-\d+\.\d+\.\d+\.msi$' | Select-Object -First 1
    if (-not $msi) { Say msi "release $($release.tag_name) has no MSI" } else {
        $sums = Sums $release $private
        $file = Join-Path $Root $msi.name
        DownloadAsset $msi $file $private
        $ok = $sums[$msi.name] -and ($sums[$msi.name] -eq (Sha256 $file))
        Say msi "$($msi.name) downloaded, SHA-256 $(if ($ok) { 'matches SHA256SUMS' } else { 'MISMATCH (refused)' })"
        if ($ok) {
            $target = Join-Path $Root 'msi-extract'
            if (Test-Path $target) { Remove-Item -Recurse -Force $target }
            $p = Start-Process msiexec.exe -ArgumentList @('/a', "`"$file`"", '/qn', "TARGETDIR=`"$target`"") -Wait -PassThru
            $exe = Get-ChildItem $target -Recurse -Filter '*.exe' -ErrorAction SilentlyContinue | Where-Object Name -match '^(InfantryAI|AgentHQ)\.exe$' | Select-Object -First 1
            Say msi "administrative extraction exit $($p.ExitCode); app exe $(if ($exe) { "present ($($exe.Name))" } else { 'not found' }) (no install: /i would upgrade the owner's app)"
        }
    }
}

if ('apk' -in $Steps -and -not $blocked) {
    $app = $serverHome
    $cmd = Join-Path $app 'bin/infantry.cmd'
    if (-not (Test-Path $cmd)) { Say apk "no headless install at $app (run the server step first)" } else {
        $daemon = RunChild $cmd @('serve') @{ INFANTRY_PORT_STRICT = '1' }
        try {
            $tokenFile = Join-Path $state 'remote.token'
            $deadline = (Get-Date).AddSeconds(90)
            $up = $false
            while ((Get-Date) -lt $deadline -and -not $up) {
                Start-Sleep -Milliseconds 500
                try { $null = Invoke-WebRequest "http://127.0.0.1:$Port/health" -UseBasicParsing -TimeoutSec 2; $up = $true } catch {}
            }
            if (-not $up) { throw "the test daemon did not come up on $Port" }
            $h = @{}
            if (Test-Path $tokenFile) { $h.Authorization = "Bearer $((Get-Content $tokenFile -Raw).Trim())" }
            $offer = Invoke-RestMethod "http://127.0.0.1:$Port/update/client?platform=android&current=$OldVersion" -Headers $h -SkipHttpErrorCheck
            Say apk "offer: result=$($offer.result) version=$($offer.version) size=$($offer.size) $($offer.message)"
            if ($offer.result -eq 'available') {
                if ($token -and $offer.url.Contains($token)) { throw 'the offer URL carries the token' }
                Say apk "download host $(([Uri]$offer.url).Host) (the signed URL itself is not printed)"
                $file = Join-Path $Root $offer.name
                Invoke-WebRequest -Uri $offer.url -OutFile $file -UseBasicParsing -Headers @{ 'User-Agent' = 'infantry-updater-e2e' }
                $ok = (Sha256 $file) -eq $offer.sha256 -and (Get-Item $file).Length -eq $offer.size
                Say apk "$($offer.name) $(if ($ok) { 'matches its size and SHA-256' } else { 'MISMATCH (refused)' })"
                if ($ok -and $Adb) {
                    $r = & adb install -r $file 2>&1
                    Say apk "adb install: $($r | Select-Object -Last 1)"
                }
            }
        } finally {
            if (-not $daemon.HasExited) { Stop-Process -Id $daemon.Id -Force }
        }
    }
}

if ('public' -in $Steps) {
    $pub = LatestRelease $false
    if ($pub -eq 'not-found') {
        Say public "updater.source=public: no token sent; $Repo answers 'not found' (expected while the repo is private)"
    } else {
        Say public "updater.source=public: no token sent; latest $($pub.tag_name) is visible (the repo is public)"
    }
    Say public "launch-day switch: updater.source=public (default), then [Environment]::SetEnvironmentVariable('INFANTRY_UPDATE_TOKEN', `$null, 'User') and delete keystore 'update-token'"
}

if ($blocked) { exit 2 }
