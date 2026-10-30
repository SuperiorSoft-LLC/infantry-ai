<#
.SYNOPSIS
  BUILD-LOC-1: packages one git sha into the project's configured build folder, or backs up the owner state.

.DESCRIPTION
  No owner path is written here. Locations come from project data (build.root, build.backupRoot), read through the
  daemon (`agenthq project config get <projectId>`, a line `build.root = <path>`), or from an env override
  (AGENTHQ_BUILD_ROOT, AGENTHQ_BUILD_BACKUPROOT). A missing setting STOPS the script (exit 2) and writes nothing.

  Build mode (-Sha):
    1. resolves the sha and requires it to be on the release branch (feature-agenthq-mvp),
    2. builds it in a clean detached worktree under %TEMP% (createDistributable, --no-parallel, Xmx3g / Metaspace 2g),
       signed with -Keystore/-KeyAlias (IHQ_STOREPASS in env), else devSign (says so in the output and manifest),
    3. copies the app image to <build.root>\builds\<sha>\, writes BUILD-INFO.txt + MANIFEST.txt there,
    4. runs copy-integrity checks (exe exists and size matches, file count and bytes match, BUILD-INFO sha matches),
    5. only after the checks pass, replaces <build.root>\current\ with the same copy.
    No tests are run. The worktree is removed afterwards.

  Backup mode (-BackupState): read-only copy of the owner state dir (-StateDir or AGENTHQ_STATE_DIR) into
  <build.backupRoot>\agenthq-state-backup\<stamp>\. The source is only read.

.EXAMPLE
  powershell -File scripts/package-dist.ps1 -Sha <sha> -DryRun        # print resolved paths, build nothing
  powershell -File scripts/package-dist.ps1 -Sha <sha>                # devSign build
  powershell -File scripts/package-dist.ps1 -Sha <sha> -Keystore <p12> -KeyAlias <alias>
  powershell -File scripts/package-dist.ps1 -BackupState -StateDir <dir> [-DryRun]
#>
[CmdletBinding()]
param(
  [string]$Sha,
  [switch]$DryRun,
  [switch]$BackupState,
  [string]$StateDir,
  [string]$Keystore,
  [string]$KeyAlias,
  [string]$ReleaseBranch = 'feature-agenthq-mvp',
  [string]$ProjectId = $env:AGENTHQ_PROJECT_ID
)

$repo = Split-Path -Parent $PSScriptRoot

function Fail([string]$Message) {
  [Console]::Error.WriteLine("PACKAGE-DIST refused: $Message")
  exit 2
}

# Reads a project-data setting: env override first, then the daemon's project config. Null when unset.
function Get-ProjectSetting([string]$Key) {
  $envName = 'AGENTHQ_' + ($Key -replace '\.', '_').ToUpper()
  $fromEnv = [Environment]::GetEnvironmentVariable($envName)
  if ($fromEnv) { return [pscustomobject]@{ Value = $fromEnv; From = "env $envName" } }
  $cli = if ($env:AGENTHQ_CLI) { $env:AGENTHQ_CLI } else { (Get-Command agenthq -ErrorAction SilentlyContinue).Source }
  if ($cli -and $ProjectId) {
    $lines = & $cli project config get $ProjectId 2>$null
    foreach ($line in $lines) {
      if ($line -match "^\s*$([regex]::Escape($Key))\s*[:=]\s*(.+?)\s*$") {
        return [pscustomobject]@{ Value = $Matches[1]; From = "daemon project config $ProjectId" }
      }
    }
  }
  return [pscustomobject]@{ Value = $null; From = $null }
}

function Get-BuildRoot {
  $setting = Get-ProjectSetting 'build.root'
  if (-not $setting.Value) {
    Fail ("build.root is not set. Nothing was written. Set project data build.root (or env AGENTHQ_BUILD_ROOT) " +
          "to an absolute folder, then run again.")
  }
  if (-not [IO.Path]::IsPathRooted($setting.Value)) { Fail "build.root must be an absolute path (got '$($setting.Value)')" }
  $root = [IO.Path]::GetFullPath($setting.Value).TrimEnd('\')
  if ($root.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { Fail "build.root must not be inside the repository" }
  return [pscustomobject]@{ Path = $root; From = $setting.From }
}

function Get-BackupRoot {
  $setting = Get-ProjectSetting 'build.backupRoot'
  if (-not $setting.Value) {
    Fail ("build.backupRoot is not set. Nothing was written. Set project data build.backupRoot " +
          "(or env AGENTHQ_BUILD_BACKUPROOT) to an absolute folder, then run again.")
  }
  if (-not [IO.Path]::IsPathRooted($setting.Value)) { Fail "build.backupRoot must be an absolute path" }
  return [pscustomobject]@{ Path = [IO.Path]::GetFullPath($setting.Value).TrimEnd('\'); From = $setting.From }
}

function Invoke-Robocopy([string]$From, [string]$To) {
  & robocopy $From $To /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
  if ($LASTEXITCODE -ge 8) { Fail "robocopy $From -> $To failed (exit $LASTEXITCODE)" }
}

function Get-Count([string]$Dir) {
  $files = @(Get-ChildItem -LiteralPath $Dir -Recurse -File)
  [pscustomobject]@{ Files = $files.Count; Bytes = ($files | Measure-Object -Property Length -Sum).Sum }
}

# ---- backup mode -------------------------------------------------------------------------------------------------

if ($BackupState) {
  $backup = Get-BackupRoot
  if (-not $StateDir) { $StateDir = $env:AGENTHQ_STATE_DIR }
  if (-not $StateDir) { Fail "no state dir given (-StateDir or env AGENTHQ_STATE_DIR). Nothing was written." }
  if (-not (Test-Path -LiteralPath $StateDir -PathType Container)) { Fail "state dir '$StateDir' does not exist" }
  $src = [IO.Path]::GetFullPath($StateDir).TrimEnd('\')
  $stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
  $dst = Join-Path $backup.Path "agenthq-state-backup\$stamp"
  if ($dst.StartsWith($src, [StringComparison]::OrdinalIgnoreCase)) { Fail "backup destination is inside the state dir" }
  if ($DryRun) {
    Write-Output "DRY-RUN backup: source=$src (read only) destination=$dst (from $($backup.From)). Nothing copied."
    exit 0
  }
  Invoke-Robocopy $src $dst
  Get-ChildItem -LiteralPath $dst -Recurse -File | ForEach-Object { $_.IsReadOnly = $true }
  $c = Get-Count $dst
  Write-Output "PACKAGE-DIST backup ok: $dst ($($c.Files) files, $($c.Bytes) bytes); source only read"
  exit 0
}

# ---- build mode --------------------------------------------------------------------------------------------------

if (-not $Sha) { Fail "no sha given (-Sha <commit>)" }
$full = (git -C $repo rev-parse --verify -q "$Sha^{commit}")
if ($LASTEXITCODE -ne 0 -or -not $full) { Fail "'$Sha' is not a commit in this repository" }
$full = $full.Trim()
git -C $repo merge-base --is-ancestor $full $ReleaseBranch 2>$null
if ($LASTEXITCODE -ne 0) { Fail "$full is not on $ReleaseBranch; build only sha's that the release branch contains" }

$root = Get-BuildRoot
$buildsDir = Join-Path $root.Path 'builds'
$dest = Join-Path $buildsDir $full
$current = Join-Path $root.Path 'current'

if ($Keystore) {
  $keystoreFull = [IO.Path]::GetFullPath((Join-Path (Get-Location) $Keystore))
  if (-not $KeyAlias) { Fail "-Keystore needs -KeyAlias" }
  if (-not $env:IHQ_STOREPASS) { Fail "signed build needs env IHQ_STOREPASS (the keystore password)" }
  $signing = 'signed'
  $signArgs = @("-Pihq.keystore=$keystoreFull", "-Pihq.alias=$KeyAlias")
} else {
  $signing = 'devSign'
  $signArgs = @('-Pihq.devSign=true')
}
$gradleArgs = @(':superiorSoftLibrary:agentHqUi:createDistributable', '--no-parallel', '--no-daemon',
  '-Dorg.gradle.jvmargs=-XX:MaxMetaspaceSize=2g -Xmx3g',
  # the worktree is detached, so the packaging guard sees HEAD; the sha was checked to be on $ReleaseBranch above
  '-PallowAnyBranch=true') + $signArgs

if ($DryRun) {
  Write-Output "DRY-RUN build: sha=$full (on $ReleaseBranch)"
  Write-Output "  build.root=$($root.Path) (from $($root.From))"
  Write-Output "  output=$dest"
  Write-Output "  current=$current"
  Write-Output "  manifest=$dest\MANIFEST.txt"
  Write-Output "  signing=$signing$(if ($signing -eq 'devSign') { ' (no key given: throwaway dev key, not for release)' })"
  Write-Output "  worktree=<TEMP>\package-dist-<sha8>-<rand> (removed after)"
  Write-Output "  gradle: .\gradlew.bat $($gradleArgs -join ' ')"
  Write-Output "Nothing built or written."
  exit 0
}

if (-not (Test-Path -LiteralPath $buildsDir)) { New-Item -ItemType Directory -Path $buildsDir | Out-Null }
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("package-dist-" + $full.Substring(0, 8) + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6))
git -C $repo worktree add --detach $tmp $full | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "could not create the worktree for $full" }

try {
  Write-Output "PACKAGE-DIST building $full ($signing) in $tmp"
  Push-Location $tmp
  try { & .\gradlew.bat @gradleArgs; $gradleExit = $LASTEXITCODE } finally { Pop-Location }
  if ($gradleExit -ne 0) { Fail "gradle createDistributable failed (exit $gradleExit). Nothing was copied." }

  $imageRoot = Join-Path $tmp 'superiorSoftLibrary\agentHqUi\build\compose\binaries\main\app'
  $image = Get-ChildItem -LiteralPath $imageRoot -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $image) { Fail "no app image under $imageRoot" }
  $srcExe = Get-ChildItem -LiteralPath $image.FullName -Filter '*.exe' -File | Select-Object -First 1
  if (-not $srcExe) { Fail "no .exe at the top of $($image.FullName)" }

  # Copy into builds\<sha>\ (a re-run replaces that sha's folder only).
  if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
  Invoke-Robocopy $image.FullName $dest
  $stampUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
  $info = @("sha=$full", "built=$stampUtc", "signing=$signing", "source=$($image.Name)")
  Set-Content -LiteralPath (Join-Path $dest 'BUILD-INFO.txt') -Value $info -Encoding ASCII
  Set-Content -LiteralPath (Join-Path $dest 'MANIFEST.txt') -Value "$full $stampUtc $signing" -Encoding ASCII

  # Copy-integrity checks: all must pass before current\ is touched.
  $destExe = Join-Path $dest $srcExe.Name
  if (-not (Test-Path -LiteralPath $destExe)) { Fail "integrity: $($srcExe.Name) missing in $dest" }
  if ((Get-Item -LiteralPath $destExe).Length -ne $srcExe.Length) { Fail "integrity: $($srcExe.Name) size differs from the build" }
  $srcCount = Get-Count $image.FullName
  $destCount = Get-Count $dest
  if ($destCount.Files -ne ($srcCount.Files + 2)) { Fail "integrity: file count $($destCount.Files) != $($srcCount.Files) + 2 (BUILD-INFO, MANIFEST)" }
  if ($destCount.Bytes -ne ($srcCount.Bytes + (Get-Item (Join-Path $dest 'BUILD-INFO.txt')).Length + (Get-Item (Join-Path $dest 'MANIFEST.txt')).Length)) {
    Fail "integrity: byte total differs from the build"
  }
  if (-not ((Get-Content -LiteralPath (Join-Path $dest 'BUILD-INFO.txt')) -contains "sha=$full")) { Fail "integrity: BUILD-INFO sha does not match $full" }

  # Only now replace current\.
  $partial = Join-Path $root.Path 'current.partial'
  if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Recurse -Force }
  Invoke-Robocopy $dest $partial
  if (Test-Path -LiteralPath $current) { Remove-Item -LiteralPath $current -Recurse -Force }
  Rename-Item -LiteralPath $partial -NewName 'current'

  Write-Output "PACKAGE-DIST ok: sha=$full signing=$signing files=$($destCount.Files) output=$dest current=$current"
} finally {
  git -C $repo worktree remove --force $tmp 2>$null
  if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}
