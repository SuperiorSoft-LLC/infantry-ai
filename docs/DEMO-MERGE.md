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
| 11 | d8c913d2 | **demo lock**: DemoLock, AccessPolicy, MailHttp, SessionManager.demoLocked, UI App/ChatPanel/ChatSurface/ProjectPicker/Model/Controller | D+G | daemon merge; UI = `ready demo=lock` -> plugins/demo/Demo_Lock (WritePolicy), core keeps `readyFields` |
| 12 | 27af052f | DemoLockTest, DemoLockModelTest | T | copy |
| 13 | 9dfbcb3d | demo-seed.env, docker-compose.demo.yml, entrypoint git repo | I+C | copy; seed content = env, not code |
| 14 | 64340268 | lock allows Resume on seeded session + GET /attention | D | in DemoLock.kt |
| 15 | 1fde7351 | UI dims Switch agent / Restart session | G | later reverted by 464f1413 (net: Restart session only) |
| 16 | 662bdcd7 | lock allows per-project reads for the demo project; UI dims write controls (RolesAttention, Board, panes...) | D+G | DemoLock.kt; UI: each pane gates writes on the generic `canWrite` (live + WritePolicy) |
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
3. **Demo-only UI behaviour** must not live in core or the official plugins (owner rule: abstract = core, specific = plugin; global demo behaviour = a demo block that wraps the app close to the root). Universal answer (PM, after phase 4a): core has a generic `WritePolicy` (`LocalWritePolicy`, default allow-all) and keeps `ready`'s fields verbatim; `plugins/demo/Demo_Lock` wraps `infantry.shell.frame` and provides the policy and the notice. `NoDemoInCoreGuardTest` keeps it that way.

## How the demo is produced from dev (after this ticket)

Two independent switches, both default off. Same source tree, no branch.

| switch | where | what it does |
|---|---|---|
| `-Pdemo=true` (Gradle) | wasm / Android build | compiles `plugins/demo/*` after the live modules (PLUGIN-OVERRIDES.md section 6). Today: `Demo_Lock` (wraps `infantry.shell.frame`; under `ready demo=lock` it provides a `WritePolicy` that refuses writes with "Disabled in the demo", and the notice) and `Demo_Banner` (the strip above the body; shows Demo_Lock's notice, i.e. the daemon's `AGENTHQ_DEMO_NOTICE` or the turn-limit message, else "DEMO BUILD - sample data"). Without the flag the generated module list is the live set, byte for byte. |
| `AGENTHQ_DEMO_LOCK=1` (env) | daemon, runtime | demo lock: default-deny frames and routes for every token, the demo project + two seeded sessions, warm-up, refuses interrupt/kill/writes. Unset = the normal daemon (DemoModeOffTest). |

Daemon demo env (read once at start; only meaningful with `AGENTHQ_DEMO_LOCK=1`; see `DemoLock.fromEnvironment` and docs/docker-deploy.md):
`AGENTHQ_DEMO_NOTICE`, `AGENTHQ_DEMO_DIR`, `AGENTHQ_DEMO_SEATS`, `AGENTHQ_DEMO_PUBLIC_TOKEN` / `_FILE`
(served at `GET /demo/visitor-token`), `AGENTHQ_DEMO_TURNS_PER_IP`, `AGENTHQ_DEMO_TURN_WINDOW_HOURS`, `AGENTHQ_DEMO_TRUSTED_PROXIES`.
Restrictions that are not demo-only but a deployment uses for it: `AGENTHQ_ALLOWED_MODELS` / `limits.models.allowed`,
`AGENTHQ_ALLOWED_ROLES` / `limits.roles.allowed`, `limits.concurrency.maxSessions`, `limits.sandbox.mode`, `limits.health.*` (all default off).

Core and the official plugins know only generic mechanisms (no `demo` name; `NoDemoInCoreGuardTest`, with a ratchet for the
SEC-FIX-1 drop-in lock files that predate this ticket):

| core mechanism | what it is | who uses it |
|---|---|---|
| `WritePolicy` / `LocalWritePolicy` / `ProvideWritePolicy` (shell/WritePolicy.kt) | may write controls be enabled, and the hover reason; default allow-all; mirrored to the app shortcuts | `LinkContext.canWrite` / `ConnectionInfo.canWrite` (= live and the nearest policy allows), `WritePolicyTooltip`, `policy.label(...)`; provided by `Demo_Lock` |
| `ConnectionInfo.readyFields` / `LinkContext.readyFields` | every `ready` key=value verbatim | `Demo_Lock` reads `demo=lock`, `demoNotice=` |
| `ready access=restricted` -> `restricted` | the daemon refuses owner-only reads | Controller skips cli locations, harness status, tool approval, secrets, processes and the command catalog; ProcessesBadge does not poll; no Assistant |
| `ready notices=code,...` -> `notices` | a refusal `<code> <message>` with a declared code is kept, not an error banner | `Demo_Lock` shows `demo_limit` (the turn limit) as its notice |

The daemon's `DemoLock` (core stopgap, default off) announces `demo=lock access=restricted notices=demo_limit demoNotice=...`.
Plugins gate write controls on `canWrite` and keep reads on `live`. Anything only the demo shows or does is a `plugins/demo/*` module.

The deployed demo = the wasm bundle built with `-Pdemo=true` served by a daemon started with `AGENTHQ_DEMO_LOCK=1`
(docker: `docker/docker-compose.yml` + `docker/docker-compose.demo.yml` + `docker/demo-seed.env`).

## Phase 5: retiring the demo-build branch (WRITTEN DOWN, NOT RUN)

Run only after DEMO-MERGE-1 is merged into dev (feature-agenthq-mvp) and the deployed demo is rebuilt from dev
(`-Pdemo=true` wasm + `AGENTHQ_DEMO_LOCK=1` daemon) and checked. From the main checkout (`C:\Users\antho\agenthq-standalone`),
PowerShell. Each step is reversible until the last `git branch -D`; the tag keeps demo-build's tip reachable anyway.

```powershell
# 0. keep the tip forever (cheap, reversible: git tag -d)
git tag archive/demo-build 46f1ae86
git push origin archive/demo-build          # only if the PM wants it on the remote

# 1. nothing uncommitted in the worktrees about to go (each must print nothing)
git -C .worktrees/demo-build status --porcelain


# 2. the demo-build worktree and branch (demo-build is ported, not merged, so -D; the tag above keeps it)
git worktree remove .worktrees/demo-build
git branch -D demo-build
# .worktrees/demo-loader (detached at 874f3c6e) is in NEITHER demo-build nor dev: see "NOT covered" below, do not remove yet

# 3. ticket branches already contained in demo-build (git merge-base --is-ancestor <b> demo-build was true on 2026-10-29)
$inDemo = @(
  'ticket/293380f7-demo-ux-new-agent-dialog-unusable-model', 'ticket/2f154f5f-demo-lock-mode-preseeded-project-2-agent',
  'ticket/9bf81983-dev-locked-demo-must-make-it-impossible', 'ticket/af4a997d-demo-switch-agent-button-must-always-be',
  'ticket/c8aa938e-demo-test-container-8816-finish-build-ve', 'ticket/d4c1dbd9-demo-image-preseeded-locked-down-single',
  'ticket/db5b3b70-demo-lock-ui-fixes-no-red-disabled-in-th', 'ticket/e75d0b32-qa-attack-the-locked-demo-through-the-br',
  'ticket/f9e181af-demo-restrictions-model-allowlist-only-o', 'ticket/fc3115a1-vc-add-the-live-demo-url-to-readme-md-on')
foreach ($b in $inDemo) {
  git merge-base --is-ancestor $b archive/demo-build; if ($LASTEXITCODE -ne 0) { "SKIP $b (not in the tag)"; continue }
  $wt = ".worktrees/" + ($b -replace '^ticket/', 'ticket-'); if (Test-Path $wt) { git worktree remove $wt }
  git branch -D $b
}

# 4. ticket branches already in dev: safe delete (-d refuses if not merged)
foreach ($b in @('ticket/12186f99-offline-demo-prompts-and-skills-panes-st', 'ticket/61e8f4ac-sec-fix-1-critical-high-demo-lock-frame',
                 'ticket/d68406d7-deploy-demo-ps1-print-infantry-progress')) {
  $wt = ".worktrees/" + ($b -replace '^ticket/', 'ticket-'); if (Test-Path $wt) { git worktree remove $wt }
  git branch -d $b
}
git worktree prune
```

NOT covered, decide first (commits in neither demo-build nor dev on 2026-10-29): the `.worktrees/demo-loader` worktree (detached 874f3c6e), `parked/d4c1dbd9-demo-extras` (2),
`ticket/2e6b444e-design-offline-demo-canned-data-web-demo` (1), `ticket/b4202754-fix-web-demo-missing-icon-glyphs-tofu-bo` (2),
`ticket/d5eb35a0-fix-web-demo-remaining-tofu-glyphs-mobil` (1). Check with `git log --oneline feature-agenthq-mvp..<branch>`.
