# Rust build speed (RUST-BUILD-SPEED-1)

Setup lives in `scripts/rust-build-env.ps1` (dot-source it). All values are settings:

| Setting | Default | Meaning |
|---|---|---|
| `INFANTRY_RUST_CACHE_ROOT` | `C:\Users\antho\agenthq-dist\cache` | Root of the cache folders |
| `CARGO_BUILD_JOBS` | `3` | Parallel rustc jobs (run at low priority) |
| `SCCACHE_DIR` | `<root>\sccache` | Shared compiled-dependency cache |
| `SCCACHE_CACHE_SIZE` | `20G` | sccache size limit |
| `CARGO_TARGET_DIR` (with `-Owner`) | `<root>\rust-target` | Persistent target dir for owner builds |

## Owner builds (the app and the daemon)
```
pwsh scripts/rust-owner-build.ps1 -SwitchScriptFrom <path to switch-to-rust.ps1>
```
The script builds the UI (`pnpm build`), builds the shell with `--features custom-protocol`, and REFUSES to package an
exe that does not embed the UI. A plain `cargo build` of the shell is a dev build: its window loads devUrl
`http://localhost:1420` and shows "localhost refused to connect" (RUST-BUG, build 4625fff6). Never package one.
Binaries land in `<root>\rust-target\owner-test\`. The daemon and the app are separate workspaces but share the
target dir, so common dependencies (tokio, sqlite, wasmtime) compile once. Use `--release` only for a real release.
`owner-test` = release without LTO, 16 codegen units, incremental, opt-level 2.

## Workers
```
. scripts/rust-build-env.ps1
```
sets `RUSTC_WRAPPER` to sccache, so a dependency compiled in one worktree is a cache hit in all others. Workers still
delete their own worktree `rust/target` when done. Check hits with `sccache --show-stats`.
Install (already done on the owner box): download the `sccache` release zip, put `sccache.exe` in `<root>\bin`.
