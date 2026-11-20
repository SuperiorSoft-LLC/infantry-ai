<!-- SPDX-License-Identifier: Apache-2.0 -->
# Build speed

What the Rust dev loop is set to, why, and what it measured on the owner's Windows PC (Ryzen 7 5700G, 16 threads,
31 GB RAM with 1 to 5 GB free while agents run, NVMe system drive). Only the dev and test builds are tuned here: the
`release` profile, the MSI and the `owner-test` profile keep their optimization (owner decision, 2026-11-20).

## What is set

| Setting | Where | Why |
|---|---|---|
| Workspace crates: `debug = "line-tables-only"` | `[profile.dev]` in `rust/Cargo.toml` and `app/src-tauri/Cargo.toml` | The dev link spends its time writing debug info (the daemon's PDB was 547 MB). Backtraces keep file and line; a debugger shows no local variables. |
| Dependencies: `debug = false`, `opt-level = 2` | `[profile.dev.package."*"]`, same files | No debug info for code we do not debug. `opt-level = 2` stays: wasmtime, SQLite, serde and tokio run 10 to 50 times slower unoptimized, and the tests use them. |
| `rust-lld.exe` linker, static CRT | repo-root `.cargo/config.toml` | Faster links with less RAM; the exes start on a clean Windows without the VC++ runtime. |
| One shared target folder | machine-local `.cargo/config.toml` and `scripts/rust-build-env.ps1` (`INFANTRY_CARGO_TARGET_DIR`) | A new worktree reuses the built dependencies instead of building its own copy. |
| sccache | `scripts/rust-build-env.ps1`, when `<cache>\bin\sccache.exe` exists | Established setup, left as it is. It only helps once the target folder was swept or wiped: it cannot cache incremental workspace crates. |

**Debugger session:** set `CARGO_PROFILE_DEV_DEBUG=full` before `cargo build` to get full debug info for the workspace
crates again (dependencies stay without). Unset it to come back. Each setting keeps its own artifacts in the target
folder, so switching rebuilds the workspace crates once, not the dependencies.

## Measured

`scripts/rust-build-bench.ps1` measured every loop: the wall time, the peak RAM of the processes the build started, and
the disk added. It waits until no other cargo build runs. A one-line edit is a body change in the crate's `lib.rs`, and
the rebuild is `cargo build -p infantry-daemon` unless named.

| Loop | Before | After | Gain |
|---|---|---|---|
| Clean `cargo build -p infantry-daemon` | 469 s, peak 5.6 GB, +6.2 GB disk | 296 s, peak 5.1 GB, +4.8 GB disk | 1.6x |
| Edit in `infantry-daemon` | 14.0 to 15.6 s, peak 3.5 GB | 7.2 to 8.7 s, peak 0.9 GB | 1.9x |
| Edit in `infantry-core` (9 crates and the daemon rebuild) | 17.0 to 21.7 s, peak 3.5 GB | 9.5 to 11.7 s, peak 1.5 GB | 1.9x |
| Edit in `infantry-protocol` | 14.6 to 17.2 s | (same path as core) | |
| Edit in a plugin, `cargo build -p infantry-notes --lib --target wasm32-wasip2` | 1.8 s | 1.8 s | none needed |
| Edit in `infantry-board`, then `cargo test -p infantry-board` | 5.5 s (2.0 s of it runs the tests) | 4.7 to 5.3 s | none needed |
| No-op `cargo build -p infantry-daemon` | 1.1 s | 1.1 s | |
| The daemon's PDB | 547 MB | 132 MB | |

The clean build "after" is measured with dependency debug info off and full workspace debug info; line tables only make
it smaller still. The first build after a clean build can take 10 s longer than a no-op: Windows Defender scans the
newly written files the first time cargo opens them (most likely; not measured further).

**Once, after this change is merged:** the first dev build in the shared target folder rebuilds the dependencies (about
5 minutes) and adds a second set of dependency artifacts (about 5 GB). Worktrees on older branches keep using the old set,
without rebuilding each other's. `scripts/cargo-target-sweep.ps1` trims the old set when it has gone unused for
`INFANTRY_SWEEP_DAYS` (7) days.

## Tried and not kept

| Lever | Result |
|---|---|
| `/DEBUG:GHASH` for rust-lld (parallel PDB type merging) | 5.0 to 5.3 s against 4.8 to 4.9 s for the daemon link: no gain. |
| `split-debuginfo` | Windows MSVC supports only `packed` (the PDB), which is already the default. |

## Not tried, and why

| Lever | Why |
|---|---|
| Cranelift backend (nightly) | It only speeds up code generation; after the change above the daemon edit is mostly the link. On Windows it has no unwinding, so a panic aborts the process and the tests that catch panics fail. |
| `cargo nextest` | One crate's tests run in about 2 s; there is little to parallelize. |
| Dependencies at `opt-level = 1` or `0` | Clean builds are rare with the shared target; the tests that run wasmtime and SQLite would get slower. |
| `codegen-units` and `incremental` for dev | Cargo's dev defaults (256 units, incremental for workspace crates) already apply. |
| Duplicate dependencies | `cargo tree -d` on the daemon: syn 2 and 3, hashbrown x3, getrandom x3, rand 0.9 and 0.10, wasm-encoder x2. All come in through other crates; trimming them means upgrading those crates. |

## Crate graph

The daemon does not depend on any plugin crate (the plugins are WASM components built on their own), so a daemon edit
never rebuilds a plugin and a plugin edit never relinks the daemon. An edit in `infantry-core` recompiles the 9 workspace
crates that depend on it, then the daemon library (about 15,000 lines) and the daemon link. Splitting the daemon is
the real fix for that path (ticket #45).

## Builds at once

A clean daemon build peaks at 5 to 6 GB of RAM with 16 jobs and takes the owner's PC down to 0.7 GB free. An edit loop
peaks at 1 to 1.5 GB. Builds that share the target folder queue on cargo's lock anyway, so this PC takes **one cargo
build at a time** (AGENTS.md rule 7). Two workers are fine because their builds queue; a third adds waiting, not speed.

## Measure again

```
# PowerShell, from the repo root. Measure in a separate target folder so the shared one is untouched.
$env:CARGO_TARGET_DIR = "$env:LOCALAPPDATA\infantry-dev\bench-target"
scripts/rust-build-bench.ps1 -Name clean-daemon -Command 'cargo build -p infantry-daemon' -Cwd rust -SizeDir $env:CARGO_TARGET_DIR
scripts/rust-build-bench.ps1 -Name noop-daemon -Command 'cargo build -p infantry-daemon' -Cwd rust
```

Each run appends one row to `%TEMP%\infantry-build-bench.csv` (`-Csv` to change it). To try a lever without editing
`Cargo.toml`, pass a config file: `cargo --config lever.toml build -p infantry-daemon` (config profiles override the
manifest). Delete the bench folder afterwards.
