<!-- SPDX-License-Identifier: Apache-2.0 -->
# A throwaway test daemon

Use a scratch daemon to reproduce a bug or try a change without touching anyone's real state. Nothing is proven until it
ran live on a test daemon.

## One command

```
scripts/test-daemon.sh start [--worktree <dir>] [--bin <infantry-daemon.exe>] [--plugins <dir>] [--name <n>]
scripts/test-daemon.sh status
scripts/test-daemon.sh stop --pid <pid>
```

The project Lua script `test-daemon` does the same (`{"action":"start"}`). `start`:

- runs the daemon detached on 127.0.0.1 and a free 189xx port (never 18990, 8787, 8788, 8797 or 8798) on a fresh temp
  state dir;
- removes every `INFANTRY_*`, `AGENTHQ_*`, `BATTALION_*` and `CLAUDE*` variable first, and sets `INFANTRY_PLUGINS_DIR` to
  `--plugins` or an empty dir;
- takes the binary from `--bin`, else a `cargo build -p infantry-distribution` in `--worktree`, else the newest packaged
  `*-rust-*` build;
- waits for `/health` 200 and prints one JSON line (url, port, pid, state dir, log file).

`stop` kills by PID only and deletes the state dir. Never stop a daemon by image name: the owner's app shares it.

## By hand

```
# from rust/
cargo run -p infantry-distribution -- --port <free 189xx port> --state-dir <new scratch dir>
curl http://127.0.0.1:<port>/health
```

- **Never 8787, 8788, 8797 or 8798**: those are the owner's daemon and UI and the PM's MCP pair (`DEV_OWNER_PORTS` in
  `rust/crates/infantry-daemon/src/health.rs`). Tests use port 0.
- Point the CLI at it with `INFANTRY_PORT=<port>` (and `INFANTRY_TOKEN` when the daemon has a token).
- Official plugins: build them with `rust/plugins/stage.sh <out-dir>` and start with `INFANTRY_PLUGINS_DIR=<out-dir>`.
- Runner repros: a second daemon with `--mode runner` (see [Remote runners](../remote-runners.md)).
- Wire checks: the `conformance` crate runs the v1 WebSocket suite against any daemon URL.
