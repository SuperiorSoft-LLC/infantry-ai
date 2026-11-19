# Dot-source before any Rust build: . scripts/rust-build-env.ps1 [-Owner]
# RUST-BUILD-SPEED-1. Every value is a named setting; override by setting the variable first.
param([switch]$Owner)
$cache = if ($env:INFANTRY_RUST_CACHE_ROOT) { $env:INFANTRY_RUST_CACHE_ROOT } else { 'C:\Users\antho\agenthq-dist\cache' }
$env:SCCACHE_DIR = if ($env:SCCACHE_DIR) { $env:SCCACHE_DIR } else { Join-Path $cache 'sccache' }
$env:SCCACHE_CACHE_SIZE = if ($env:SCCACHE_CACHE_SIZE) { $env:SCCACHE_CACHE_SIZE } else { '20G' }
$sccache = Join-Path $cache 'bin\sccache.exe'
if (Test-Path $sccache) { $env:RUSTC_WRAPPER = $sccache }
# Owner builds share ONE target dir so the second build is incremental. Workers keep their own rust/target.
if ($Owner) { $env:CARGO_TARGET_DIR = Join-Path $cache 'rust-target' }
