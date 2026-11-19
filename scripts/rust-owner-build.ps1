# SPDX-License-Identifier: Apache-2.0
# Copyright (c) SuperiorSoft LLC
# RUST-BUG (4625fff6 window "localhost refused to connect"): the ONE owner-test build of the Rust app + daemon.
# Builds the UI bundle, builds the shell WITH the custom-protocol feature (embeds shell/dist, never loads devUrl),
# asserts the exe embeds the UI, then packages <DistRoot>\builds\<stamp>-rust-<sha>\. Never launches anything.
param([string]$DistRoot = 'C:\Users\antho\agenthq-dist', [string]$SwitchScriptFrom = '', [switch]$SkipMsi)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'rust-build-env.ps1') -Owner
$t0 = Get-Date
$sha = (git -C $repo rev-parse --short=8 HEAD).Trim()
Push-Location (Join-Path $repo 'app'); try { pnpm install --frozen-lockfile; Push-Location shell; pnpm build; Pop-Location } finally { Pop-Location }
$index = Join-Path $repo 'app\shell\dist\index.html'
if (-not (Test-Path $index)) { throw "shell dist missing: $index" }
cargo build --profile owner-test --manifest-path (Join-Path $repo 'rust\Cargo.toml') -p infantry-daemon
cargo build --profile owner-test --features custom-protocol --manifest-path (Join-Path $repo 'app\src-tauri\Cargo.toml')
$out = Join-Path $env:CARGO_TARGET_DIR 'owner-test'
# RUST-BUG dd10c3bb: stage every official daemon plugin (WASM) into $out\plugins. Without this the package has no
# plugins folder and every plugin frame (Triggers, Notes, image_upload) answers "does not serve that frame yet".
$stagedPlugins = Join-Path $out 'plugins'
if (Test-Path $stagedPlugins) { Remove-Item $stagedPlugins -Recurse -Force }
# Git Bash, never C:\Windows\system32\bash.exe (that is WSL: no cargo, no python on its PATH).
$gitRoot = Split-Path (Get-Command git).Source -Parent; while ($gitRoot -and -not (Test-Path (Join-Path $gitRoot 'bin\bash.exe'))) { $gitRoot = Split-Path $gitRoot -Parent }
$gitBash = Join-Path $gitRoot 'bin\bash.exe'
if (-not (Test-Path $gitBash)) { throw "BUILD REFUSED: Git Bash not found at $gitBash" }
& $gitBash (Join-Path $repo 'rust/plugins/stage.sh') $stagedPlugins release
if ($LASTEXITCODE -ne 0) { throw 'BUILD REFUSED: plugin staging failed' }
$nWasm = @(Get-ChildItem $stagedPlugins -Recurse -Include *.wasm,*.component -ErrorAction SilentlyContinue).Count
$nMods = @(Get-ChildItem (Join-Path $repo 'rust\plugins\official') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'module.json') }).Count
if ($nWasm -lt 1) { throw 'BUILD REFUSED: no daemon plugin was staged (no .wasm under plugins)' }
Write-Output "staged $nWasm daemon plugin component(s) of $nMods module(s)"
# STATIC CHECK: a dev-mode exe has no embedded assets; an embedded one carries the dist asset paths.
$asset = (Get-ChildItem (Join-Path $repo 'app\shell\dist\assets') -Filter '*.js' | Select-Object -First 1).Name
$bytes = [IO.File]::ReadAllBytes((Join-Path $out 'infantry-tauri.exe'))
$text = [Text.Encoding]::Latin1.GetString($bytes)
if (-not $text.Contains($asset)) { throw "BUILD REFUSED: InfantryAI-Rust.exe (infantry-tauri.exe) does not embed the UI ($asset not found); it would load devUrl localhost:1420. Build with --features custom-protocol." }
$dest = Join-Path $DistRoot ("builds\{0}-rust-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $sha)
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item (Join-Path $out 'infantry-tauri.exe') (Join-Path $dest 'InfantryAI-Rust.exe')
foreach ($f in 'infantry-daemon.exe','infantry-migrate.exe','infantry.exe') { Copy-Item (Join-Path $out $f) $dest }
Copy-Item $stagedPlugins $dest -Recurse
Set-Content (Join-Path $dest 'start.cmd') "@echo off`r`nrem Rust app, default port 18990, own state dir (%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust)`r`nset INFANTRY_PORT=18990`r`nset INFANTRY_PLUGINS_DIR=%~dp0plugins`r`nstart `"`" `"%~dp0InfantryAI-Rust.exe`""
if ($SwitchScriptFrom) { Copy-Item $SwitchScriptFrom (Join-Path $dest 'switch-to-rust.ps1') }

# RUST-INSTALLER (60bc4e42): also produce the per-user MSI (the installable, auto-updating form) next to the folder build.
# Release-profile exes are staged by stage-bundle.sh; the MSI is signed with the TEST updater key unless TAURI_SIGNING_PRIVATE_KEY is set.
if (-not $SkipMsi) {
    $env:INFANTRY_BUNDLE_PROFILE = 'release'
    & $gitBash (Join-Path $repo 'app/src-tauri/scripts/stage-bundle.sh'); if ($LASTEXITCODE -ne 0) { throw 'MSI: staging failed' }
    $cfg = @('--config', 'src-tauri/bundle-stage/tauri.bundle.conf.json')
    if (-not $env:TAURI_SIGNING_PRIVATE_KEY) {
        $kd = Join-Path $env:TEMP "infantry-msi-key-$sha"; New-Item -ItemType Directory -Force $kd | Out-Null
        Push-Location (Join-Path $repo 'app'); try { pnpm tauri signer generate --ci -p '' -w "$kd/test.key" | Out-Null } finally { Pop-Location }
        $env:TAURI_SIGNING_PRIVATE_KEY = Get-Content "$kd/test.key" -Raw; $env:TAURI_SIGNING_PRIVATE_KEY_PASSWORD = ''
        $ov = Join-Path $kd 'ov.json'; (@{ plugins = @{ updater = @{ pubkey = (Get-Content "$kd/test.key.pub" -Raw).Trim() } } } | ConvertTo-Json -Depth 5) | Set-Content $ov
        $cfg += @('--config', $ov); Write-Output 'MSI signed with a THROWAWAY test key (no installed app trusts it)'
    }
    Push-Location (Join-Path $repo 'app')
    try { pnpm tauri build --bundles msi @cfg --config '{"bundle":{"createUpdaterArtifacts":true}}'; if ($LASTEXITCODE -ne 0) { throw 'MSI build failed' } } finally { Pop-Location }
    $msiDir = Join-Path $env:CARGO_TARGET_DIR 'release/bundle/msi'
    if (-not (Test-Path $msiDir)) { $msiDir = Join-Path $repo 'app/src-tauri/target/release/bundle/msi' }
    Copy-Item (Join-Path $msiDir '*.msi*') $dest
    Write-Output "MSI: $((Get-ChildItem $dest -Filter *.msi | Select-Object -First 1).FullName)"
}
$min = [math]::Round(((Get-Date) - $t0).TotalMinutes, 1)
Set-Content (Join-Path $dest 'README.txt') "Infantry AI (Rust/Tauri) owner-test build $sha. Run start.cmd (port 18990). UI embedded (custom-protocol, checked). Build time: $min min (cached, incremental)."
Write-Output "BUILD OK $dest ($min min)"
