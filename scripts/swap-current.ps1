<#
.SYNOPSIS
  BUILD-SWAP-1: switches <build.root>\current to a finished build without ever leaving current\ half-deleted.

.DESCRIPTION
  Design: copy-then-two-renames. builds\<sha>\ is the complete, verified build and is never touched here.
    1. copy builds\<sha> -> current.next (current\ is not touched),
    2. verify current.next (BUILD-INFO sha, file count, bytes, exe size) against builds\<sha>,
    3. rename current -> current.old. A running app locks files, so this rename fails; then NOTHING has changed:
       the script reports "blocked by running process <pid> <exe>" (never kills) and exits 3,
    4. rename current.next -> current (rolls current.old back if this fails),
    5. verify current against builds\<sha>, then delete current.old and the old leftovers
       (current.partial, current.broken, copy-*) ONLY after that verification.
  Exit codes: 0 switched and verified (or already current), 2 refused/failed (message says what), 3 blocked by a running process
  (READY-TO-SWITCH.txt written in the build root). Never prints "ok" after an error.
  The build root comes from -BuildRoot, env AGENTHQ_BUILD_ROOT, or the daemon project config (build.root). No kill, no app launch.

.EXAMPLE
  powershell -File scripts/swap-current.ps1 -WhatIf          # show the plan, change nothing
  powershell -File scripts/swap-current.ps1                  # switch to the sha named in READY-TO-SWITCH.txt (or the newest build)
  powershell -File scripts/swap-current.ps1 -Sha <sha>
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
  [string]$Sha,
  [string]$BuildRoot = $env:AGENTHQ_BUILD_ROOT,
  [string]$ProjectId = $env:AGENTHQ_PROJECT_ID
)

function Fail([string]$Message, [int]$Code = 2) {
  [Console]::Error.WriteLine("SWAP-CURRENT failed: $Message")
  exit $Code
}

if (-not $BuildRoot) {
  $cli = if ($env:AGENTHQ_CLI) { $env:AGENTHQ_CLI } else { (Get-Command agenthq -ErrorAction SilentlyContinue).Source }
  if ($cli -and $ProjectId) {
    foreach ($line in (& $cli project config get $ProjectId 2>$null)) {
      if ($line -match '^\s*build\.root\s*[:=]\s*(.+?)\s*$') { $BuildRoot = $Matches[1]; break }
    }
  }
}
if (-not $BuildRoot) { Fail "build.root is not set (-BuildRoot, env AGENTHQ_BUILD_ROOT or project data build.root). Nothing was touched." }
if (-not [IO.Path]::IsPathRooted($BuildRoot)) { Fail "build root must be an absolute path (got '$BuildRoot')" }
$root = [IO.Path]::GetFullPath($BuildRoot).TrimEnd('\')
$buildsDir = Join-Path $root 'builds'
$current = Join-Path $root 'current'
$next = Join-Path $root 'current.next'
$old = Join-Path $root 'current.old'
$readyFile = Join-Path $root 'READY-TO-SWITCH.txt'

if (-not $Sha -and (Test-Path -LiteralPath $readyFile)) {
  foreach ($l in (Get-Content -LiteralPath $readyFile)) { if ($l -match '^sha=([0-9a-f]{7,40})\s*$') { $Sha = $Matches[1]; break } }
}
if (-not $Sha) {
  $newest = Get-ChildItem -LiteralPath $buildsDir -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'BUILD-INFO.txt') } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($newest) { $Sha = $newest.Name }
}
if (-not $Sha) { Fail "no build found under $buildsDir. Nothing was touched." }
$src = Join-Path $buildsDir $Sha
if (-not (Test-Path -LiteralPath $src -PathType Container)) {
  # PKG-DIST-1: folders are builds\<yyyyMMdd-HHmmss>-<sha8>; -Sha may be a sha (prefix) or a folder name. Several builds of one sha: the newest.
  $m = @(Get-ChildItem -LiteralPath $buildsDir -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "$Sha*" -or $_.Name -like "*-$($Sha.Substring(0, [Math]::Min(8, $Sha.Length)))*" } | Sort-Object Name -Descending)
  if ($m.Count -lt 1) { Fail "no build named or containing '$Sha' under $buildsDir. Nothing was touched." }
  $src = $m[0].FullName
}
$Folder = Split-Path -Leaf $src
$infoSha = $null
$infoFile = Join-Path $src 'BUILD-INFO.txt'
if (Test-Path -LiteralPath $infoFile) { foreach ($l in (Get-Content -LiteralPath $infoFile)) { if ($l -match '^sha=([0-9a-f]{40})\s*$') { $infoSha = $Matches[1]; break } } }
if (-not $infoSha) { Fail "no sha= line in $infoFile. Nothing was touched." }
$Sha = $infoSha  # from here on $Sha is the full commit sha; $Folder names the build folder

function Get-Count([string]$Dir) {
  $files = @(Get-ChildItem -LiteralPath $Dir -Recurse -File -Force)
  [pscustomobject]@{ Files = $files.Count; Bytes = [int64](($files | Measure-Object -Property Length -Sum).Sum) }
}

# Throws a message when $Dir is not an exact, complete copy of the build in $src.
function Test-SameAsBuild([string]$Dir) {
  $info = Join-Path $Dir 'BUILD-INFO.txt'
  if (-not (Test-Path -LiteralPath $info)) { return "no BUILD-INFO.txt in $Dir" }
  if (-not ((Get-Content -LiteralPath $info) -contains "sha=$Sha")) { return "BUILD-INFO sha in $Dir is not $Sha" }
  $a = Get-Count $src; $b = Get-Count $Dir
  if ($a.Files -ne $b.Files) { return "file count $($b.Files) in $Dir != $($a.Files) in the build" }
  if ($a.Bytes -ne $b.Bytes) { return "byte total in $Dir differs from the build" }
  foreach ($e in (Get-ChildItem -LiteralPath $src -Filter '*.exe' -File)) {
    $t = Join-Path $Dir $e.Name
    if (-not (Test-Path -LiteralPath $t) -or (Get-Item -LiteralPath $t).Length -ne $e.Length) { return "$($e.Name) in $Dir differs from the build" }
  }
  return $null
}

function Get-Blockers {
  $WhatIfPreference = $false
  $prefix = $root + '\'
  @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase) } |
    Select-Object -First 5 | ForEach-Object { "pid $($_.ProcessId) $($_.ExecutablePath)" })
}

function Get-Leftovers {
  @('current.partial', 'current.broken', 'current.old', 'current.next') | ForEach-Object { Join-Path $root $_ } | Where-Object { Test-Path -LiteralPath $_ }
  Get-ChildItem -LiteralPath $root -Directory -Filter 'copy-*' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName }
}

Write-Output "SWAP-CURRENT plan: build.root=$root sha=$Sha"
Write-Output "  1 copy  builds\$Folder -> current.next (current\ untouched)"
Write-Output "  2 verify current.next (BUILD-INFO sha, file count, bytes, exe size)"
Write-Output "  3 rename current -> current.old (fails while the app runs: then stop, nothing changed, exit 3)"
Write-Output "  4 rename current.next -> current, verify current"
Write-Output "  5 delete after verification: $((@(Get-Leftovers | ForEach-Object { Split-Path -Leaf $_ }) -join ', '))"
$blockers = Get-Blockers
Write-Output "  running from this root now: $(if ($blockers) { $blockers -join '; ' } else { 'none' })"
if (-not $PSCmdlet.ShouldProcess("$root\current", "switch to builds\$Folder")) { Write-Output "WhatIf: nothing changed."; exit 0 }

# Already current and complete: only clean up.
if ((Test-Path -LiteralPath $current) -and -not (Test-SameAsBuild $current)) {
  Write-Output "current\ already is $Sha and complete."
} else {
  if (Test-Path -LiteralPath $next) { Remove-Item -LiteralPath $next -Recurse -Force -ErrorAction Stop }
  & robocopy $src $next /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -ge 8) { Fail "robocopy builds\$Folder -> current.next failed (exit $LASTEXITCODE). current\ untouched." }
  $bad = Test-SameAsBuild $next
  if ($bad) { Fail "verification of current.next: $bad. current\ untouched." }

  if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force -ErrorAction Stop }
  if (Test-Path -LiteralPath $current) {
    try { Rename-Item -LiteralPath $current -NewName 'current.old' -ErrorAction Stop }
    catch {
      $who = Get-Blockers
      $why = if ($who) { "blocked by running process " + ($who -join '; ') } else { "blocked: $($_.Exception.Message)" }
      Set-Content -LiteralPath $readyFile -Encoding ASCII -Value @(
        "sha=$Sha", "ready=builds\$Folder (complete and verified; current.next holds a verified copy)",
        "current\ was NOT changed because: $why",
        "Close Infantry AI, then run:  powershell -File `"$PSScriptRoot\swap-current.ps1`" -BuildRoot `"$root`"")
      [Console]::Error.WriteLine("SWAP-CURRENT blocked: $why. Nothing was changed. See $readyFile")
      exit 3
    }
  }
  try { Rename-Item -LiteralPath $next -NewName 'current' -ErrorAction Stop }
  catch {
    if (Test-Path -LiteralPath $old) { Rename-Item -LiteralPath $old -NewName 'current' -ErrorAction SilentlyContinue }
    Fail "could not move current.next into place ($($_.Exception.Message)); rolled current\ back"
  }
  $bad = Test-SameAsBuild $current
  if ($bad) { Fail "verification of the new current\: $bad. The previous one is kept in current.old" }
}

# Verified: now (and only now) remove the leftovers.
$failed = @()
foreach ($p in @(Get-Leftovers)) {
  try { Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction Stop } catch { $failed += (Split-Path -Leaf $p) }
}
if (Test-Path -LiteralPath $readyFile) { Remove-Item -LiteralPath $readyFile -Force -ErrorAction SilentlyContinue }
if ($failed) { Write-Output "SWAP-CURRENT switched to $Sha and verified, but could not delete: $($failed -join ', ') (locked; harmless, run again later)" }
else { Write-Output "SWAP-CURRENT done: current\ is $Sha, verified, leftovers removed." }
exit 0
