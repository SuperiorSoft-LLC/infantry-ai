# Dot-source before any Rust build: . scripts/rust-build-env.ps1 [-Owner]
# RUST-BUILD-SPEED-1. Every value is a named setting; override by setting the variable first.
param([switch]$Owner)
$cache = if ($env:INFANTRY_RUST_CACHE_ROOT) { $env:INFANTRY_RUST_CACHE_ROOT } else { 'C:\Users\antho\agenthq-dist\cache' }
$env:SCCACHE_DIR = if ($env:SCCACHE_DIR) { $env:SCCACHE_DIR } else { Join-Path $cache 'sccache' }
$env:SCCACHE_CACHE_SIZE = if ($env:SCCACHE_CACHE_SIZE) { $env:SCCACHE_CACHE_SIZE } else { '20G' }
$sccache = Join-Path $cache 'bin\sccache.exe'
if (Test-Path $sccache) { $env:RUSTC_WRAPPER = $sccache }
# STORAGE-2: EVERY build (owner and workers, any worktree) shares ONE target dir outside the worktrees, so N worktrees
# cost one set of dependency artifacts, not N rust/target folders. Cargo locks it, so builds queue (AGENTS.md rule 7).
# Same folder as the machine-local <checkout>\.cargo\config.toml and ui-live-preview.ps1. Setting: INFANTRY_CARGO_TARGET_DIR.
# Trimmed by scripts/cargo-target-sweep.ps1 (run after each MERGED step). -Owner is kept for callers; it no longer differs.
$sharedTarget = if ($env:INFANTRY_CARGO_TARGET_DIR) { $env:INFANTRY_CARGO_TARGET_DIR } else { Join-Path $env:LOCALAPPDATA 'infantry-dev\preview-target' }
if (-not $env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR = $sharedTarget }
