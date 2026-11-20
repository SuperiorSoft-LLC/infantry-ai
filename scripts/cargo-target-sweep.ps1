# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# STORAGE-2: keep the ONE shared cargo target dir bounded. Runs as a plain command at the end of every MERGED step,
# right after the worker removes its worktree (no AgentsRoom trigger, no model run, never an OS scheduled task).
#
# Why not cargo-sweep: cargo 1.100 lays the build dir out as <profile>\build\<package>\<hash>\{fingerprint,out}
# (no deps\ or .fingerprint\); cargo-sweep 0.8 does not read that layout and trims nothing. Cargo has no target-dir
# GC of its own (`cargo clean gc` only covers ~/.cargo caches). So this script trims the new layout itself:
#   1. a unit <profile>\build\<package>\<hash> whose fingerprint cargo has not read for -Days days is deleted
#      (cargo reads the fingerprint on every build that uses the unit, which updates its last-access time);
#   2. an incremental session <profile>\incremental\<crate-hash> not written for -Days days is deleted;
#   3. with -MaxSize, the least recently used units go next until the folder fits.
# Everything deleted is rebuildable; the worst case is a slower next build. A profile whose cargo lock is held (a
# build is running) is skipped, and the lock is held while that profile is trimmed, so a build waits its turn.
#
#   pwsh -NoProfile -File scripts/cargo-target-sweep.ps1            # settings below
#   pwsh -NoProfile -File scripts/cargo-target-sweep.ps1 -DryRun    # report only
# Settings (each a named setting; a parameter wins over its env var):
#   INFANTRY_CARGO_TARGET_DIR   the shared target dir (default %LOCALAPPDATA%\infantry-dev\preview-target)
#   INFANTRY_SWEEP_DAYS         age limit in days (default 7)
#   INFANTRY_SWEEP_MAX_SIZE_GB  optional size cap in GB (default empty = no cap)
param(
  [string]$TargetDir = $(if ($env:INFANTRY_CARGO_TARGET_DIR) { $env:INFANTRY_CARGO_TARGET_DIR } else { Join-Path $env:LOCALAPPDATA 'infantry-dev\preview-target' }),
  [double]$Days = $(if ($env:INFANTRY_SWEEP_DAYS) { [double]$env:INFANTRY_SWEEP_DAYS } else { 7 }),
  [double]$MaxSizeGB = $(if ($env:INFANTRY_SWEEP_MAX_SIZE_GB) { [double]$env:INFANTRY_SWEEP_MAX_SIZE_GB } else { 0 }),
  [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $TargetDir)) { Write-Host "[sweep] nothing to do: $TargetDir does not exist"; exit 0 }
$cutoff = (Get-Date).AddDays(-$Days)
$driveName = (Get-Item -LiteralPath $TargetDir).PSDrive.Name

function Get-Bytes([string]$path) {
  [long](Get-ChildItem -LiteralPath $path -Recurse -File -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
}
function Format-GB([long]$bytes) { [Math]::Round($bytes / 1GB, 2) }
function Get-LastUsed([string]$dir) {
  $times = Get-ChildItem -LiteralPath $dir -Recurse -File -Force -ErrorAction SilentlyContinue |
    ForEach-Object { $_.LastAccessTime; $_.LastWriteTime }
  if ($times) { ($times | Measure-Object -Maximum).Maximum } else { (Get-Item -LiteralPath $dir).LastWriteTime }
}

# Take cargo's own locks for one profile dir (same LockFileEx range cargo uses), or return $null if a build holds them.
function Lock-Profile([string]$profileDir) {
  $held = @()
  foreach ($name in '.cargo-lock', '.cargo-build-lock', '.cargo-artifact-lock') {
    $file = Join-Path $profileDir $name
    if (-not (Test-Path -LiteralPath $file)) { continue }
    try {
      $fs = [IO.File]::Open($file, 'Open', 'ReadWrite', [IO.FileShare]'ReadWrite, Delete')
      $fs.Lock(0, [long]::MaxValue)
      $held += $fs
    } catch {
      if ($fs) { $fs.Dispose() }
      $held | ForEach-Object { $_.Dispose() }
      return $null
    }
  }
  , $held
}

# Profile dirs: <target>\<profile> and <target>\<triple>\<profile>, i.e. any dir holding build\ or incremental\.
$profiles = Get-ChildItem -LiteralPath $TargetDir -Directory -Force | ForEach-Object { $_; Get-ChildItem -LiteralPath $_.FullName -Directory -Force } |
  Where-Object { (Test-Path -LiteralPath (Join-Path $_.FullName 'build')) -or (Test-Path -LiteralPath (Join-Path $_.FullName 'incremental')) }

$beforeBytes = Get-Bytes $TargetDir
$freeBefore = [Math]::Round((Get-PSDrive $driveName).Free / 1GB, 1)
Write-Host "[sweep] $TargetDir = $(Format-GB $beforeBytes) GB, free on ${driveName}: $freeBefore GB, age limit $Days days$(if ($MaxSizeGB -gt 0) { ", cap $MaxSizeGB GB" })$(if ($DryRun) { ' (dry run)' })"

$removed = 0; $removedBytes = [long]0; $skipped = @(); $kept = @()
foreach ($p in $profiles) {
  $locks = Lock-Profile $p.FullName
  if ($null -eq $locks) { $skipped += $p.FullName; Write-Host "[sweep] skip $($p.FullName): a build holds its cargo lock"; continue }
  try {
    $units = @()
    $build = Join-Path $p.FullName 'build'
    if (Test-Path -LiteralPath $build) {
      $units += Get-ChildItem -LiteralPath $build -Directory -Force | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Directory -Force } |
        ForEach-Object {
          $fp = Join-Path $_.FullName 'fingerprint'
          [pscustomobject]@{ Profile = $p.FullName; Path = $_.FullName; LastUsed = (Get-LastUsed $(if (Test-Path -LiteralPath $fp) { $fp } else { $_.FullName })) }
        }
    }
    $incr = Join-Path $p.FullName 'incremental'
    if (Test-Path -LiteralPath $incr) {
      $units += Get-ChildItem -LiteralPath $incr -Directory -Force | ForEach-Object { [pscustomobject]@{ Profile = $p.FullName; Path = $_.FullName; LastUsed = $_.LastWriteTime } }
    }
    foreach ($u in $units) {
      if ($u.LastUsed -lt $cutoff) {
        $bytes = Get-Bytes $u.Path; $removed++; $removedBytes += $bytes
        if (-not $DryRun) { Remove-Item -LiteralPath $u.Path -Recurse -Force -ErrorAction SilentlyContinue }
      } else { $kept += $u }
    }
  } finally { $locks | ForEach-Object { $_.Dispose() } }
}
Write-Host "[sweep] age: $(if ($DryRun) { 'would remove' } else { 'removed' }) $removed units older than $Days days ($(Format-GB $removedBytes) GB)"

if ($MaxSizeGB -gt 0 -and $skipped.Count -eq 0) {
  $size = $beforeBytes - $removedBytes; $capBytes = [long]($MaxSizeGB * 1GB); $capped = 0; $cappedBytes = [long]0
  foreach ($u in ($kept | Sort-Object LastUsed)) {
    if ($size -le $capBytes) { break }
    $locks = Lock-Profile $u.Profile
    if ($null -eq $locks) { continue }
    try {
      $bytes = Get-Bytes $u.Path; $size -= $bytes; $capped++; $cappedBytes += $bytes
      if (-not $DryRun) { Remove-Item -LiteralPath $u.Path -Recurse -Force -ErrorAction SilentlyContinue }
    } finally { $locks | ForEach-Object { $_.Dispose() } }
  }
  Write-Host "[sweep] cap: $(if ($DryRun) { 'would remove' } else { 'removed' }) $capped least recently used units ($(Format-GB $cappedBytes) GB)"
}

$afterBytes = Get-Bytes $TargetDir
$freeAfter = [Math]::Round((Get-PSDrive $driveName).Free / 1GB, 1)
Write-Host "[sweep] done: $(Format-GB $beforeBytes) GB -> $(Format-GB $afterBytes) GB, free on ${driveName}: $freeBefore GB -> $freeAfter GB$(if ($skipped) { ", skipped $($skipped.Count) busy profile(s)" })"
