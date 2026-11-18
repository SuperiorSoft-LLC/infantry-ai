# Rust port: performance profile (RUST-PERF-1, 2026-11-18)

This compares the installed Kotlin build `20261117-181728-6afaed5e` with the Tauri app on `feature-rust-port`. Both ran the same scenario on the same Windows 11 PC, one run after the other. The two are **not feature-equal**: read "What is not comparable" before you draw a conclusion.

## Results

MB = working set / private bytes, averaged over the idle window. CPU is % of ONE core; 100% is one core fully busy.

| | Kotlin 1 | Tauri 1 | Kotlin 5 | Tauri 5 | Kotlin 10 | Tauri 10 |
|---|---|---|---|---|---|---|
| App / daemon process, no agents | 559 / 661 | 25 / 5 | 546 / 640 | 25 / 6 | 551 / 643 | 25 / 6 |
| App / daemon, agents idle | 585 / 672 | 26 / 6 | 591 / 679 | 28 / 9 | 610 / 702 | 30 / 11 |
| WebView2 (6 procs), agents idle | n/a (in JVM) | 375 / 224 | n/a | 380 / 279 | n/a | 387 / 296 |
| MCP bridges (cmd + JVM per agent) | 114 / 171 | none | 580 / 856 | none | 1165 / 1689 | none |
| Agent CLIs (claude.exe) | 302 / 554 | 304 / 549 | 1559 / 2865 | 1516 / 2775 | 3048 / 5512 | 3005 / 5502 |
| Consoles (conhost) | 20 / 4 | 10 / 2 | 101 / 19 | 49 / 11 | 201 / 37 | 96 / 22 |
| **Total working set, idle** | **~1021** | **~724** | **~2855** | **~2003** | **~5022** | **~3518** |
| **Total excluding the agent CLIs** | **~719** | **~420** | **~1296** | **~487** | **~1975** | **~513** |
| App CPU, no agents | 6.8% | 0.0% | 4.8% | 0.2% | 7.2% | 0.0% |
| App CPU, agents idle | 106% | 0.1% | 124% | 0.8% | 80% | 1.5% |
| App CPU while streaming | 102% | 0% (+23% webview) | 151% | 0.6% (+23% webview) | 230% | 1.5% (+28% webview) |
| MCP bridge CPU while streaming | 17% | none | 35% | none | 44% | none |
| Agent CLI CPU while streaming | 34% | 14% | 92% | 73% | 151% | 161% |
| UI fps while streaming (avg / worst 1 s / p99 frame) | not measured | 75 / 72 / 13.5 ms | not measured | 75 / 50 / 13.5 ms | not measured | 75 / 45 / 13.6 ms |
| Cold start: window shown | 4392 ms | 105 ms | 3204 ms | 60 ms | 3211 ms | 75 ms |
| Cold start: ready (Kotlin /health, Tauri UI connected) | 15040 ms | 638 ms | 13794 ms | 602 ms | 13606 ms | 1650 ms |
| Processes left after the kill | 0 | 0 | 0 | 0 | 0 | 0 |

The display refresh rate was 75 Hz, so 75 fps is the ceiling. No frame took longer than 50 ms.

### Sizes

| | Kotlin | Tauri |
|---|---|---|
| Installer | not measured (the build dir has no installer) | 2.3 MB (NSIS) |
| Exe / launcher | 0.55 MB launcher | 6.2 MB exe |
| Installed footprint | 321 MB (JVM runtime + jars) | ~6.2 MB, plus the system WebView2 that Windows ships |

### MCP bridge alone (`scripts/perf/bridges.ps1`, 3 bridges of each kind against a test daemon)

| | JVM `infantry mcp serve` (installed today) | native Rust `infantry mcp serve` (RUST-MCP-1, not switched on) |
|---|---|---|
| Processes per bridge | 2 (cmd + java) | 1 |
| Memory per bridge, ws / private | 103 / 169 MB | 7.4 / 1.7 MB |
| Spawn to tools/list answer | 1.7 - 2.0 s | 0.9 - 1.1 s |

## What the numbers say

- **The agent CLIs are the same on both sides**, ~300 MB working set and ~550 MB private each. At 10 agents that is ~3 GB of the total, and no app choice changes it.
- **The app's own share is what the port changes.** Excluding the agent CLIs, Kotlin uses ~0.7 GB at 1 agent and ~2.0 GB at 10. Tauri uses ~0.4 GB at 1 and ~0.5 GB at 10.
- **Most of Kotlin's growth is the per-agent JVM MCP bridge**, ~115 MB working set for each agent. That is fixable without the UI port: the native Rust bridge needs ~7 MB.
- **Kotlin's idle CPU is high.** With agents idle the JVM process uses about one full core (80-124%). With no agents it uses 5-7%. This looks like a busy-poll or render loop per session, and it deserves its own bug check whatever is decided about the port.
- **Tauri's fixed cost is WebView2**, ~350 MB working set (~225 MB private) with nothing open. That is most of Tauri's non-agent memory, and it grows little with agents (+40 MB at 10).
- **Startup:** about 0.6 s to a connected UI on Tauri, against about 14 s to /health on Kotlin. Kotlin's window appears after about 3.2-4.4 s.

## Method

- **Harness:** `scripts/perf/compare.ps1`, with helpers in `lib.ps1`. A default run covers both apps at 1, 5 and 10 agents with a 20 s idle window, using the haiku model.
- **Kotlin:** a separate instance of the installed build on port 18911 (`INFANTRY_INSTALL_PORT`), with temp state (`INFANTRY_INSTALL_STATE_DIR`). The environment is stripped of `INFANTRY_*`, `AGENTHQ_*` and `CLAUDE*`. `kotlin-driver.mjs` drives it over the WebSocket: it saves a throwaway project, spawns N agents, and sends each the prompts.
- **Tauri:** `infantry-tauri.exe` with `INFANTRY_UI_LOCAL=1`, so the runner is embedded in the app. `INFANTRY_PERF_SCENARIO` makes the page itself run the same steps (`app/shell/src/daemon/perf-scenario.ts`) and report events to a file.
- **Scenario:**
  1. Spawn N agents; each answers "Reply with the word ok and nothing else."
  2. Wait 2 s, then idle for 20 s.
  3. Send every agent "Write about 300 words about rivers..." at once, and run until all have finished.
- **Sampling:** the whole process tree, about once a second, via Win32_Process (parents and children by PID). Each process gets a role:
  - the root is the app;
  - msedgewebview2 is the webview;
  - claude.exe is an agent;
  - java under a claude chain, or the cmd that starts it, is an MCP bridge;
  - conhost is a console.

  Memory is WorkingSet64 and PrivateMemorySize64. CPU is the change in TotalProcessorTime per PID over the window.
- **Baseline:** Kotlin is sampled for 8 s after 15 s of settling, before any agent. Tauri uses the 9 s before its scenario spawns, at 25 s after start.
- **Startup:** "window" is the first MainWindowHandle. "Ready" is Kotlin's first 200 from `/health`, or the Tauri page's `ui-ready` event, both timed from launch.
- **fps:** measured in the page with a requestAnimationFrame counter during the stream phase.
- **Cleanup:** every tree is killed by PID after the run; leftovers were 0 every time. The throwaway project folders were deleted.
- **Functional check of the Tauri agents:** `scripts/perf/ui-smoke.ps1` drives the page over the WebView2 DevTools protocol (DOM events in the page, no desktop input). All 8 checks pass:
  - new agent card;
  - a turn completes;
  - the reply shows in the transcript;
  - Stop interrupts;
  - two agents run at once;
  - the resume list shows the session;
  - /exit;
  - `--resume` remembers the earlier turn.

## Caveats

- One run per cell, on a desktop with other work going on. Treat differences under ~10% as noise.
- Measurements:
  - **Startup is warm-cache.** Both apps had run before, so the OS file cache was warm. A first start after a reboot will be slower, more so for the 321 MB JVM install.
  - **Working sets overlap** (shared DLLs and pages are counted once per process). The "total" rows are sums, so they overstate absolute use. They are fair for comparing the two sides, because both sides are summed the same way.
  - **The per-role CPU split is approximate.** A process that starts or exits within the window is counted only for the part it was alive.
- Kotlin side:
  - Kotlin's UI and daemon share one JVM process, so its "App / daemon" row includes the Compose UI. Its rendering is not split out.
  - **Kotlin UI fps was not measured.** Kotlin is frozen, and the Compose window has no in-page hook. A WebDriver-style probe does not exist for it.
  - Kotlin's stream phase took 15-19 s against Tauri's 6-8 s. This is the wall time until every agent finished. Kotlin's harness also goes through MCP bridge startup and the daemon's queue, so part of the gap is real overhead and part is a different turn path. Neither side is waiting on the UI.
- Tauri side:
  - **Tauri gets text per assistant message, not per token** (the runner parses stream-json records). Its UI renders fewer, larger updates than a token stream would need. The fps numbers are real, but on a lighter load.

## What is not comparable (Rust feature gaps)

The Tauri app with the embedded runner is a sessions-only prototype. Compared with the Kotlin app it has:

- **No daemon:** no WebSocket server, no other clients (web, Android, CLI), no remote runners.
- **No board:**
  - no tickets, epics, mail or COMMS;
  - no project knowledge;
  - no Lua scripts or hooks;
  - no plugins;
  - no settings.
- **No MCP bridge per agent.** The agents get no `infantry_*` tools at all, which is the biggest reason the memory numbers differ. The native Rust bridge exists (see the bridge table above), but it is not wired into the Tauri app. If it were, it would add about 7 MB working set and 1 process per agent.
- **Approvals and questions:** tool approvals are allow-all, and an agent's questions are left unanswered.
- **No persistence:** no session list across app restarts beyond resuming from `~/.claude/projects`. No usage or pace tracking, no worktrees or git branches per agent.
- **Claude only.** Other harnesses are not ported.

So the Tauri numbers are a floor for a full Rust app, not its final cost. The parts that are measured like for like are:

- the shell (window, UI process and startup);
- the agent CLIs;
- the MCP bridge, measured on its own.

## Reproduce

```
pwsh scripts/perf/compare.ps1                      # both apps, 1/5/10 agents (~10 min, small haiku prompts)
pwsh scripts/perf/compare.ps1 -Apps tauri -Agents 1
pwsh scripts/perf/bridges.ps1                      # JVM vs native MCP bridge
pwsh scripts/perf/ui-smoke.ps1                     # functional check of the Tauri agents
```

Ports: 18911 for the Kotlin test daemon, 18912 for the bridge test and 18930 for WebView2 DevTools. Every script refuses ports outside 189xx and never touches 8787/8788/8797/8798.
