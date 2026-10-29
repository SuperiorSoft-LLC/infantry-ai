# DEMO-MERGE-1: folding the demo-build branch into dev (ticket 3b521473)

Goal: no long-lived demo branch. The demo = the same build in **demo mode** (`AGENTHQ_DEMO_*` env, runtime, default OFF)
plus the **demo plugin set** (`plugins/demo/*`, `-Pdemo=true` on wasm/Android, see PLUGIN-OVERRIDES.md section 6).

Base `db250c9a`; demo-build `46f1ae86` is 31 commits ahead (75 files, +3869 -219). Dev already has a *smaller* demo lock
(SEC-FIX-1 `FrameGate`: `AGENTHQ_DEMO_LOCK`, default-deny frame list, `PluginFolders.demoLock()`). The demo-build `DemoLock`
is the richer version (seeded project + 2 sessions, visitor token, per-IP turn limit, per-project reads, refuse interrupt/kill).
They layer: FrameGate stays as the first gate; `DemoLock` adds the seed, the finer per-frame decisions and the HTTP routes.

Classes: **D** daemon runtime, default off - **G** generic UI gate (a flag the shell reads, default off) - **C** demo content
(becomes a demo plugin) - **I** infra/docker - **T** tests - **X** already in dev / obsolete.

| # | commit | what | class | port |
|---|--------|------|-------|------|
| 1 | 43d0749c | docker demo image (opencode+pi, token in state volume) | I | copy docker/ (dev docker/ unchanged since base) |
| 2 | 6d05250f | sandbox mode `limits.sandbox.*`, `Sandbox.kt` at the runner seam | D | 3-way merge; default `off` |
| 3 | 714cf08a | docker: sandbox on, landlock-exec, sandbox-check | I | copy |
| 4 | 85b95e75 | model allowlist `limits.models.allowed` / `AGENTHQ_ALLOWED_MODELS` | D+G | daemon merge; UI = ExecutorPicker filter (empty list = no filter) |
| 5 | 8602c449 | role allowlist `limits.roles.allowed` | D+G | same; empty = all roles |
| 6 | 6cb76fce | picker reason line first line only | G | UI re-apply |
| 7 | 910cff3e | compose leaves roles empty | I | copy |
| 8 | e069594e | SQLite hang: sqlite-jdbc 3.51.3 pin, `/health` HealthGate + watchdog | D | merge; **fixes a production hang, not demo-only** |
| 9 | 0183eb1a | maxSessions counts OPEN sessions | D | merge (changes a limit's meaning; see risk 2) |
| 10 | 279bd652 | Stop-agent button only while maxSessions reached | G | UI re-apply, follows 9 |
| 11 | d8c913d2 | **demo lock**: DemoLock, AccessPolicy, MailHttp, SessionManager.demoLocked, UI App/ChatPanel/ChatSurface/ProjectPicker/Model/Controller | D+G | daemon merge; UI = `ready demo=lock` -> `Model.demoLocked` |
| 12 | 27af052f | DemoLockTest, DemoLockModelTest | T | copy |
| 13 | 9dfbcb3d | demo-seed.env, docker-compose.demo.yml, entrypoint git repo | I+C | copy; seed content = env, not code |
| 14 | 64340268 | lock allows Resume on seeded session + GET /attention | D | in DemoLock.kt |
| 15 | 1fde7351 | UI dims Switch agent / Restart session | G | later reverted by 464f1413 (net: Restart session only) |
| 16 | 662bdcd7 | lock allows per-project reads for the demo project; UI dims write controls (RolesAttention, Board, panes...) | D+G | DemoLock.kt; UI via one `canWrite` helper in the plugin that owns each pane |
| 17 | 4e47438e | lock allows skill_list; Skills tab dimmed | D+G | as 16 |
| 18 | df0ce0fd | picker rows read as options, sole-model auto-pick | G | UI re-apply + SoleModelPickTest |
| 19 | ed0b8bff | New agent closes on every path under the lock | G | UI |
| 20 | 464f1413 | Switch agent not dimmed (revert part of 15) | G | UI (net result only) |
| 21 | ee0675bd, 69cc356c, 6ba9d4ca | New-agent/picker visual polish (background, Cancel/Apply, label position) | G | UI re-apply (cosmetic, lowest priority) |
| 22 | 3dd0dbb0 | lock refuses interrupt + kill | D+G | DemoLock.kt |
| 23 | 4e6a7722 | role row Edit dimmed, role editor closed | G | UI |
| 24 | 27f5a367 | no command catalog / no `/` command under the lock | G | UI |
| 25 | 034d5e26, 1dac95e5 | warm-up: each seeded session starts its first run at start-up; failed/orphaned = not warmed | D | DemoLock.kt/DaemonMain |
| 26 | c78ecc9c | public visitor token `GET /demo/visitor-token`; web Main.kt reads it | D+G | daemon; wasm Main.kt: generic "fetch visitor token if server offers one" |
| 27 | 46f1ae86 | per-IP turn limit (`DemoTurnLimit`, `/data/demo-turns.tsv`) | D | copy |
| - | 63669b72 etc | merges | X | - |

Already in dev, not re-ported: offline demo canned data (web demo), demo/live module split in `generateModuleList`, `Demo_Banner`.
Obsolete: the `Palette.Raised`/layout polish of rows 21 where the owning pane already moved into an `Infantry_*` plugin and changed.

## Expected conflicts
`DaemonMain.kt` (+234, dev has +hundreds since base), `SessionManager.kt`, `SettingsService.kt`, `LimitRegistry.kt`, `AccessPolicy.kt`:
resolved by a 3-way `git merge-file` per file (base db250c9a), not a branch merge. UI: `Model.kt`, `Controller.kt`, `App.kt`,
`NewAgentDialog`, `RolesAttention`, `ChatSurface`, `ChatPanel` now live in `agenthqUiCore`/`Infantry_*`; a branch merge would resurrect moved files, so the UI
intent is re-applied by hand on the current owners.

## Hurdles and universal mechanisms
1. **Two demo locks** (FrameGate on dev, DemoLock on demo-build). Universal answer: one switch (`AGENTHQ_DEMO_LOCK`), FrameGate first, then `DemoLock` decisions; both default off.
2. **Limits that change meaning** (maxSessions counts open sessions). Kept behind the existing limit; reported, not hidden.
3. **Demo-only UI text/seed** must not live in the shell. Universal answer: the daemon sends `ready demo=lock demoNotice=...` (data), the shell reads a generic `demoLocked` flag + notice; anything visual is a `plugins/demo/*` override.

## How the demo is produced from dev (after this ticket)

Two independent switches, both default off. Same source tree, no branch.

| switch | where | what it does |
|---|---|---|
| `-Pdemo=true` (Gradle) | wasm / Android build | compiles `plugins/demo/*` after the live modules (PLUGIN-OVERRIDES.md section 6). Today: `Demo_Banner` (the strip above the body; shows the daemon's `AGENTHQ_DEMO_NOTICE` when the daemon sends one, else "DEMO BUILD - sample data"). Without the flag the generated module list is the live set, byte for byte. |
| `AGENTHQ_DEMO_LOCK=1` (env) | daemon, runtime | demo lock: default-deny frames and routes for every token, the demo project + two seeded sessions, warm-up, refuses interrupt/kill/writes. Unset = the normal daemon (DemoModeOffTest). |

Daemon demo env (read once at start; only meaningful with `AGENTHQ_DEMO_LOCK=1`; see `DemoLock.fromEnvironment` and docs/docker-deploy.md):
`AGENTHQ_DEMO_NOTICE`, `AGENTHQ_DEMO_DIR`, `AGENTHQ_DEMO_SEATS`, `AGENTHQ_DEMO_PUBLIC_TOKEN` / `_FILE`
(served at `GET /demo/visitor-token`), `AGENTHQ_DEMO_TURNS_PER_IP`, `AGENTHQ_DEMO_TURN_WINDOW_HOURS`, `AGENTHQ_DEMO_TRUSTED_PROXIES`.
Restrictions that are not demo-only but a deployment uses for it: `AGENTHQ_ALLOWED_MODELS` / `limits.models.allowed`,
`AGENTHQ_ALLOWED_ROLES` / `limits.roles.allowed`, `limits.concurrency.maxSessions`, `limits.sandbox.mode`, `limits.health.*` (all default off).

The shell itself knows only generic data: `LinkContext.demoLock`, `LinkContext.canWrite` (= `live && !demoLock`) and
`LinkContext.demoNotice`. Plugins gate write controls on `canWrite` and keep reads on `live`. Anything visual that only the
demo shows is a `plugins/demo/*` module.

The deployed demo = the wasm bundle built with `-Pdemo=true` served by a daemon started with `AGENTHQ_DEMO_LOCK=1`
(docker: `docker/docker-compose.yml` + `docker/docker-compose.demo.yml` + `docker/demo-seed.env`).
