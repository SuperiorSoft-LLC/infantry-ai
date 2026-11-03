<#
.SYNOPSIS
  BUILD-LOC-1: packages one git sha into the project's configured build folder, or backs up the owner state.

.DESCRIPTION
  No owner path is written here. Locations come from project data (build.root, build.backupRoot), read through the
  daemon (`infantry project config get <projectId>`, a line `build.root = <path>`), or from an env override
  (INFANTRY_BUILD_ROOT, INFANTRY_BUILD_BACKUPROOT). A missing setting STOPS the script (exit 2) and writes nothing.

  Build mode (-Sha):
    1. resolves the sha and requires it to be on the release branch ("feature-agenthq"-mvp),
    2. builds it in a clean detached worktree under %TEMP% (createDistributable, --no-parallel, Xmx3g / Metaspace 2g),
       signed with -Keystore/-KeyAlias (IHQ_STOREPASS in env), else devSign (says so in the output and manifest),
    3. copies the app image to <build.root>\builds\<sha>\, writes BUILD-INFO.txt + MANIFEST.txt there,
    4. runs copy-integrity checks (exe exists and size matches, file count and bytes match, BUILD-INFO sha matches),
    5. only after the checks pass, replaces <build.root>\current\ with the same copy.
    BUILD-PLUGINS-1: -Preset / -Include / -Exclude pick which plugins the build carries (ids, classes and presets are data in
    plugins/build-presets.json; default = everything). Core and official plugins cannot be left out (error with the reason).
    Left-out plugins are absent from the staged modules, the daemon halves and the signed set; the build REPORTS included/excluded in
    builds\<sha>.build-manifest.json and (when anything is left out) packages them signed in builds\<sha>.optional-plugins\ with a
    catalog.json (id, version, class, jar, sha256, grants) for the plugin manager's local-directory catalog.
    No tests are run. The worktree is removed afterwards.

  Backup mode (-BackupState): read-only copy of the owner state dir (-StateDir or INFANTRY_STATE_DIR) into
  <build.backupRoot>\infantry-state-backup\<stamp>\. The source is only read.

.EXAMPLE
  powershell -File scripts/package-dist.ps1 -Sha <sha> -DryRun        # print resolved paths, build nothing
  powershell -File scripts/package-dist.ps1 -Sha <sha>                # devSign build
  powershell -File scripts/package-dist.ps1 -Sha <sha> -Keystore <p12> -KeyAlias <alias>
  powershell -File scripts/package-dist.ps1 -Sha <sha> -Preset no-optional        # leave every optional plugin out
  powershell -File scripts/package-dist.ps1 -Sha <sha> -Exclude Infantry_Stats    # leave one out
  powershell -File scripts/package-dist.ps1 -BackupState -StateDir <dir> [-DryRun]
#>
[CmdletBinding()]
param(
  [string]$Sha,
  [switch]$DryRun,
  [switch]$SwitchCurrent,    # PKG-DIST-1: opt in to replacing <build.root>\current after a verified build (default: never)
  [switch]$BackupState,
  [string]$StateDir,
  [string]$Keystore,
  [string]$KeyAlias,
  [string[]]$Include,       # BUILD-PLUGINS-1: keep only these optional plugins (the other optional ones are left out)
  [string[]]$Exclude,       # BUILD-PLUGINS-1: leave these optional plugins out (core/official are refused)
  [string]$Preset,          # BUILD-PLUGINS-1: a named preset from plugins/build-presets.json (full, no-optional, core-only)
  [string]$ReleaseBranch = '"feature-agenthq"-mvp',
  [string]$ProjectId = $env:INFANTRY_PROJECT_ID
)

$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'plugin-selection.ps1')

$script:Step = 'start'
$script:BuildOk = $false
$script:DestCreated = $null
function Fail([string]$Message) {
  [Console]::Error.WriteLine("PACKAGE-DIST refused at step '$($script:Step)': $Message")
  exit 2
}

# Reads a project-data setting: env override first, then the daemon's project config. Null when unset.
function Get-ProjectSetting([string]$Key) {
  $envName = 'INFANTRY_' + ($Key -replace '\.', '_').ToUpper()
  $fromEnv = [Environment]::GetEnvironmentVariable($envName)
  if ($fromEnv) { return [pscustomobject]@{ Value = $fromEnv; From = "env $envName" } }
  $cli = if ($env:INFANTRY_CLI) { $env:INFANTRY_CLI } else { (Get-Command infantry -ErrorAction SilentlyContinue).Source }
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
    Fail ("build.root is not set. Nothing was written. Set project data build.root (or env INFANTRY_BUILD_ROOT) " +
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
          "(or env INFANTRY_BUILD_BACKUPROOT) to an absolute folder, then run again.")
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
  if (-not $StateDir) { $StateDir = $env:INFANTRY_STATE_DIR }
  if (-not $StateDir) { Fail "no state dir given (-StateDir or env INFANTRY_STATE_DIR). Nothing was written." }
  if (-not (Test-Path -LiteralPath $StateDir -PathType Container)) { Fail "state dir '$StateDir' does not exist" }
  $src = [IO.Path]::GetFullPath($StateDir).TrimEnd('\')
  $stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
  $dst = Join-Path $backup.Path "infantry-state-backup\$stamp"
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

# BUILD-PLUGINS-1: resolve the selection first, so a refused one (core excluded, unknown id) stops before anything is written.
$pluginsRoot = Join-Path $repo 'plugins'
try {
  $selection = Resolve-PluginSelection @(Get-PluginInventory $pluginsRoot) $pluginsRoot $Include $Exclude $Preset
} catch { Fail "plugin selection: $($_.Exception.Message)" }

Write-Output "PACKAGE-DIST step 1/7: resolve"
$root = Get-BuildRoot
$buildsDir = Join-Path $root.Path 'builds'
$folderName = (Get-Date).ToString('yyyyMMdd-HHmmss') + '-' + $full.Substring(0, 8)  # PKG-DIST-1: every build gets its own folder
$dest = Join-Path $buildsDir $folderName
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
$gradleArgs = @(':superiorSoftLibrary:infantryUi:createDistributable', '--no-parallel', '--no-daemon',
  '-Dorg.gradle.jvmargs=-XX:MaxMetaspaceSize=2g -Xmx3g',  # quoted per argument when run (Invoke-Gradle)
  # the worktree is detached, so the packaging guard sees HEAD; the sha was checked to be on $ReleaseBranch above
  '-PallowAnyBranch=true') + $signArgs
if ($selection.Excluded.Count -gt 0) { $gradleArgs += @("-Pihq.excludePlugins=$($selection.Excluded -join ',')", '-Pihq.optionalOut=true') }

if ($DryRun) {
  Write-Output "DRY-RUN build: sha=$full (on $ReleaseBranch)"
  Write-Output "  build.root=$($root.Path) (from $($root.From))"
  Write-Output "  output=$dest"
  Write-Output "  current=$current"
  Write-Output "  manifest=$dest\MANIFEST.txt"
  Write-Output "  plugins: preset=$(if ($Preset) { $Preset } else { 'full' }) included=$($selection.Included.Count) excluded=[$($selection.Excluded -join ', ')]"
  Write-Output "  build-manifest=$buildsDir\$folderName.build-manifest.json optional-plugins=$buildsDir\$folderName.optional-plugins\ (when anything is excluded)"
  Write-Output "  signing=$signing$(if ($signing -eq 'devSign') { ' (no key given: throwaway dev key, not for release)' })"
  Write-Output "  worktree=<TEMP>\package-dist-<sha8>-<rand> (removed after)"
  Write-Output "  gradle: .\gradlew.bat $($gradleArgs -join ' ')"
  Write-Output "Nothing built or written."
  exit 0
}

if (-not (Test-Path -LiteralPath $buildsDir)) { New-Item -ItemType Directory -Path $buildsDir | Out-Null }
Write-Output "PACKAGE-DIST step 2/7: worktree"
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("package-dist-" + $full.Substring(0, 8) + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6))
git -C $repo worktree add --detach $tmp $full | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "could not create the worktree for $full" }

try {
  Write-Output "PACKAGE-DIST building $full ($signing) in $tmp"
  Push-Location $tmp
Write-Output "PACKAGE-DIST step 3/7: gradle"
  $script:Step = 'gradle createDistributable'
  # Every argument is double-quoted into one cmd.exe line: gradlew.bat splits unquoted -Pname=value on '=' and a jvmargs value on spaces.
  $quoted = ($gradleArgs | ForEach-Object { '"' + ($_ -replace '"', '\"') + '"' }) -join ' '
  # Start-Process passes the command line verbatim (a direct call re-escapes the inner quotes).
  $cmdLine = '/d /s /c ""' + $tmp + '\gradlew.bat" ' + $quoted + '"'
  try { $gp = Start-Process -FilePath cmd.exe -ArgumentList $cmdLine -NoNewWindow -Wait -PassThru; $gradleExit = $gp.ExitCode } finally { Pop-Location }
  if ($gradleExit -ne 0) { Fail "gradle createDistributable failed (exit $gradleExit). Nothing was copied." }

  $script:Step = 'locate app image'
  $imageRoot = Join-Path $tmp 'superiorSoftLibrary\infantryUi\build\compose\binaries\main\app'
  $image = Get-ChildItem -LiteralPath $imageRoot -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $image) { Fail "no app image under $imageRoot" }
  $srcExe = Get-ChildItem -LiteralPath $image.FullName -Filter '*.exe' -File | Select-Object -First 1
  if (-not $srcExe) { Fail "no .exe at the top of $($image.FullName)" }

  Write-Output "PACKAGE-DIST step 4/7: copy"
  $script:Step = 'copy image'
  # Copy into builds\<sha>\ (a re-run replaces that sha's folder only).
  if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
  $script:DestCreated = $dest
  Invoke-Robocopy $image.FullName $dest
  $script:Step = 'write BUILD-INFO/MANIFEST/README'
  $stampUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
  $info = @("sha=$full", "built=$stampUtc", "signing=$signing", "source=$($image.Name)")
  $infoPath = Join-Path $dest 'BUILD-INFO.txt'
  if (Test-Path -LiteralPath $infoPath) { Add-Content -LiteralPath $infoPath -Value (@('', '# package-dist') + $info) -Encoding ASCII }  # keep the image's own record
  else { Set-Content -LiteralPath $infoPath -Value $info -Encoding ASCII }
  Set-Content -LiteralPath (Join-Path $dest 'MANIFEST.txt') -Value "$full $stampUtc $signing" -Encoding ASCII
  # README.txt is generated per build (never copied from an earlier build), so it names this sha and folder.
  $readme = @("Infantry AI build $($full.Substring(0, 7))", "sha=$full", "folder=$(Split-Path -Leaf $dest)", "built=$stampUtc", "signing=$signing", "See BUILD-INFO.txt for the full record.")
  Set-Content -LiteralPath (Join-Path $dest 'README.txt') -Value $readme -Encoding ASCII

  Write-Output "PACKAGE-DIST step 5/7: integrity"
  $script:Step = 'copy-integrity checks'
  # Copy-integrity checks: all must pass before current\ is touched.
  $destExe = Join-Path $dest $srcExe.Name
  if (-not (Test-Path -LiteralPath $destExe)) { Fail "integrity: $($srcExe.Name) missing in $dest" }
  if ((Get-Item -LiteralPath $destExe).Length -ne $srcExe.Length) { Fail "integrity: $($srcExe.Name) size differs from the build" }
  $srcCount = Get-Count $image.FullName
  $destCount = Get-Count $dest
  # The image may already carry its own BUILD-INFO.txt (the build writes one); our copy replaces it, so count only what is added.
  $addFiles = 0; $addBytes = 0
  foreach ($n in 'BUILD-INFO.txt', 'MANIFEST.txt', 'README.txt') {
    $s = Join-Path $image.FullName $n
    $d = (Get-Item -LiteralPath (Join-Path $dest $n)).Length
    if (Test-Path -LiteralPath $s) { $addBytes += $d - (Get-Item -LiteralPath $s).Length } else { $addFiles++; $addBytes += $d }
  }
  if ($destCount.Files -ne ($srcCount.Files + $addFiles)) { Fail "integrity: file count $($destCount.Files) != $($srcCount.Files) + $addFiles (BUILD-INFO, MANIFEST, README)" }
  if ($destCount.Bytes -ne ($srcCount.Bytes + $addBytes)) {
    Fail "integrity: byte total differs from the build"
  }
  if (-not ((Get-Content -LiteralPath (Join-Path $dest 'BUILD-INFO.txt')) -contains "sha=$full")) { Fail "integrity: BUILD-INFO sha does not match $full" }

  Write-Output "PACKAGE-DIST step 6/7: plugins"
  $script:Step = 'plugin report'
  # BUILD-PLUGINS-1: the included/excluded report and the left-out plugins' packages, next to the build.
  $inventory = @(Get-PluginInventory (Join-Path $tmp 'plugins'))
  $manifestPath = Join-Path $buildsDir "$folderName.build-manifest.json"
  $optionalDest = Join-Path $buildsDir "$folderName.optional-plugins"
  if (Test-Path -LiteralPath $optionalDest) { Remove-Item -LiteralPath $optionalDest -Recurse -Force }
  if ($selection.Excluded.Count -gt 0) {
    $optionalSrc = Join-Path $tmp 'superiorSoftLibrary\infantryUi\build\official-modules\optional-plugins'
    if (-not (Test-Path -LiteralPath $optionalSrc)) { Fail "no optional-plugins output at $optionalSrc" }
    Invoke-Robocopy (Join-Path $optionalSrc 'official') $optionalDest
    $catalog = New-OptionalCatalog $optionalDest $inventory
    if (@($catalog.plugins).Count -ne $selection.Excluded.Count) { Fail "catalog has $(@($catalog.plugins).Count) plugin(s), expected $($selection.Excluded.Count)" }
    $catalog | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $optionalDest 'catalog.json') -Encoding UTF8
  }
  $report = New-BuildManifest $inventory $selection $full $signing $Preset
  $stagedOfficial = @(Get-ChildItem -LiteralPath (Join-Path $dest 'app\resources\modules\official') -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.Name })
  foreach ($x in $selection.Excluded) { if ($stagedOfficial -contains $x) { Fail "integrity: excluded plugin $x is still in the build" } }
  $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
  Write-Output "PACKAGE-DIST plugins: included=$($selection.Included.Count) excluded=[$($selection.Excluded -join ', ')] report=$manifestPath$(if ($selection.Excluded.Count) { " optional=$optionalDest" })"

  $script:Step = 'finish'
  Write-Output "PACKAGE-DIST step 7/7: finish"
  if ($SwitchCurrent) {
    # BUILD-SWAP-1: never delete-then-copy. swap-current.ps1 copies, verifies, then renames; a running app only blocks the switch.
    $swap = Join-Path $PSScriptRoot 'swap-current.ps1'
    & powershell -NoProfile -ExecutionPolicy Bypass -File $swap -Sha $folderName -BuildRoot $root.Path
    $swapExit = $LASTEXITCODE
    if ($swapExit -eq 3) {
      Write-Output "PACKAGE-DIST ok (NOT switched): sha=$full signing=$signing files=$($destCount.Files) output=$dest. current\ is untouched because the app is running; to switch run: powershell -File `"$swap`" -Sha $folderName -BuildRoot `"$($root.Path)`""
    } elseif ($swapExit -ne 0) {
      Fail "swap of current\ failed (exit $swapExit); the build is complete and verified in $dest"
    } else {
      Write-Output "PACKAGE-DIST ok: sha=$full signing=$signing files=$($destCount.Files) output=$dest current=$current"
    }
  } else {
    Write-Output "PACKAGE-DIST ok: sha=$full signing=$signing files=$($destCount.Files) output=$dest (current\ untouched; add -SwitchCurrent to switch)"
  }
  $script:BuildOk = $true
} finally {
  if (-not $script:BuildOk -and $script:DestCreated -and (Test-Path -LiteralPath $script:DestCreated)) {
    Remove-Item -LiteralPath $script:DestCreated -Recurse -Force -ErrorAction SilentlyContinue  # never leave a half build
    Remove-Item -LiteralPath $manifestPath -Force -ErrorAction SilentlyContinue
  }
  git -C $repo worktree remove --force $tmp 2>$null
  if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue }
}
