# UI verify design: workers see and check the real UI without the owner's desktop

UI-VERIFY-1 (ticket 5c2fd1e4, 2026-11-08). Design record: findings, chosen design, rejected alternatives, ticket
breakdown, open owner decisions. No code was changed for it. Where this page and the code disagree, the code wins.

## 1. The gap

MASCOT-2, MASCOT-3, AVATAR-SEAM-1/2, AVATAR-PICKER-1 and RUNNER-E2E-1 (scenario 8) each closed with "real-app
screenshot check NOT done: needs clicks or a browser, workers may not do that: needs owner". UI tickets ship unseen,
and MASCOT-3 measured frame cost with the UI test's virtual clock, so `focus` and `all` motion measured the same and
the real cost of `all` is unknown.

The rule that stands: workers never send synthetic input to the owner's desktop (a worker click once landed in the
owner's Chrome). The fix is a bridge that never opens a window on, captures, or types into the owner's desktop.

## 2. What exists today (verified in the code)

| Thing | Where | What it does | Safe for a worker? |
|---|---|---|---|
| `runUi` / `run` | `superiorSoftLibrary/infantryUi/build.gradle.kts` | The real desktop window against the daemon on 8787. | No: a real window on the owner's desktop, the owner's daemon. |
| `runRealDaemon` | same file | In-process daemon on 8788 plus a real window. | No: real window, the owner's preview port. |
| `runSmoke` | same file, `SmokeMain.kt` | A real window (`application { Window }`) on an in-process stub daemon, temp state. | No: still a real window. |
| `runTabShots`, `renderTabShotsForDiff`, `diffTabShots` | same file, `TabShotsMain.kt`, `TabShotDiff.kt` | Offscreen: the desktop UI test harness (`runDesktopComposeUiTest(1400, 860)`) renders the whole `App` on an in-process stub daemon (temp dir, ephemeral port) and writes one PNG per tab with `captureToImage`; the diff compares them with tab-shots-baseline/ pixel by pixel. | Yes. This is the seam to build on. |
| `runStartSessionShots` and about ten `*Shots.kt` files | `superiorSoftLibrary/infantryUi/src/test/kotlin/com/superiorsoftllc/infantry/ui/` | One-off PNG renderers, each with its own setup (AgentMenuShots, ComposerShots, ConfigDialogShots, ...). | Yes, but each is a copy of the same machinery (rule 9 debt). |
| About 250 `*UiTest.kt` | same folder | Desktop UI tests driving the UI by semantics (`onNodeWithTag(...).performClick()`), offscreen. | Yes: test-scoped input, never OS input. |
| `LongChatMeasureUiTest.kt` | same folder | Measures load and append time with `System.nanoTime()` inside the UI test harness. | Yes, but frames advance on the virtual clock, so animations never cost anything. |
| `perfProfile` (`PerfProfileMain.kt`) | `superiorSoftLibrary/infantryUi/src/test/kotlin/com/superiorsoftllc/infantry/ui/perf/` | Boots a daemon JVM and the REAL UI JVM (MainKt, a window) in a temp home on a free port and records RSS, heap, NMT, CPU. Memory and CPU only, no frame time. | Isolated from the owner's daemon and state (`PerfGuard.kt`), but it opens a real window. |
| `PerfGuardTest.kt` | same folder | The PERF-1 isolation guard: refuses ports 8787/8788, paths in `~/.infantry`, a child JVM without a temp home. | It is NOT a frame-time budget. AGENTS.md section 6 ("UI perf budget") and the mascot design (MASCOT-3: "PerfGuardTest green") describe it wrongly. |
| `scripts/testdaemon.sh` | repo root | Starts a scrubbed test daemon from a dist on 18900-18999, refuses 8787/8788/8797/8798, temp state, kills by PID. | Yes. The daemon for the web check. |
| `WebBundle.kt` | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/` | The daemon serves the wasm client at `/` from `INFANTRY_WEB_DIR` (or the app's web folder). | Yes: a test daemon can serve a freshly built bundle. |
| Web or Android UI tests | none | No browser test task, no Playwright/Selenium, no emulator, no Robolectric. `infantryAndroid` has no sources; the UI is `infantryUiCore` commonMain plus 18 androidMain files. | n/a |
| Browsers on the machine | Windows | Edge always present; Chrome installed. Both support `--headless=new` and the DevTools protocol. JDK 21 has `java.net.http` (HTTP and WebSocket). | A headless instance with its own profile directory is a separate process tree with no window. |

## 3. Chosen design: four tooling mechanisms, no core code

Everything lives in test source sets, `scripts/`, a Gradle task and a wiki page. Nothing goes into core main code,
no new dependency is added, and every mechanism reuses the PERF-1 isolation guard instead of writing its own checks.

### 3.1 UI shot: render any screen offscreen to a PNG plus a semantics dump

One generic runner replaces the copy-per-feature `*Shots.kt` pattern for new work:

```
./gradlew :superiorSoftLibrary:infantryUi:uiShot -PuiShot=<scenario> [-PuiShotSteps=<steps.json>] [-PuiShotOut=<dir>] [-PuiShotSize=1400x860]
```

- It is the `TabShotsMain.kt` machinery made reusable: in-process stub daemon, temp state dir, ephemeral port, seed
  data, the desktop UI test harness, `captureToImage`, PNG out. A scenario is a named entry (the whole `App`, one
  tab, one panel, one dialog) registered in one Kotlin list in the infantryUi test source set; a plugin adds its own
  scenario there (a later seam can let a plugin's test fixtures register one).
- Next to each PNG it writes `<name>.semantics.txt`: the semantics tree (tag, text, content description, bounds).
  A worker reads text far cheaper than an image and can assert on it; the PNG is for the look (Claude's Read tool
  shows a PNG).
- The JVM runs with `-Djava.awt.headless=true`, so an accidental real window fails with HeadlessException instead of
  appearing on the owner's desktop. The acceptance checks that the UI test harness works under that flag; if a Compose
  path needs AWT, the fallback is an end-of-run assertion that no AWT window was ever created.

Cost: the infantryUi test classpath JVM (the shipped caps from `jvm-caps.txt`, about 1 GB in use); 1 to 3 s per
shot after the test source set is compiled (a cold compile of that source set is minutes, normally already done in a
UI worktree). One Gradle build at a time (AGENTS.md rule 7).

### 3.2 UI steps: drive the offscreen UI with test-scoped input

`-PuiShotSteps=<file>` takes a small JSON list run inside the same harness, by semantics only:

```json
[
  {"size": "800x600"},
  {"click": {"tag": "agents.row.0"}},
  {"click": {"text": "Settings"}},
  {"type": {"tag": "composer.input", "text": "hello"}},
  {"key": "Enter"},
  {"waitIdle": true},
  {"shot": "after-send"},
  {"assertText": "hello"}
]
```

A step maps to `performClick`, `performTextInput`, `performKeyInput` and `waitForIdle` on the test harness. No step
can reach OS input: the harness delivers events to its own offscreen scene. A failing step (node not found) writes a
shot and the semantics dump of the moment it failed, then exits non-zero. Data, not Kotlin, so a worker needs no
compile to try a flow; a flow worth keeping becomes a normal `*UiTest`.

### 3.3 Frame bench: real-clock frame cost, offscreen

The UI test harness drives frames from a virtual clock (`mainClock`): an infinite animation costs nothing there, which
is why MASCOT-3 measured `focus` and `all` the same. The bench uses the offscreen scene directly (ImageComposeScene,
the same Skia raster surface the harness renders into) and calls render with the real `System.nanoTime()` in a loop
paced at 60 Hz for a fixed window (default 10 s after a 2 s warm-up):

```
./gradlew :superiorSoftLibrary:infantryUi:frameBench -PframeBench=<scenario> [-PframeBenchVariants=static,focus,all] [-PframeBenchSeconds=10]
```

It reports, per variant, in one JSON file plus a one-line summary:

- **work frames per second while idle**: frames in which the scene had invalidations (composition, layout or draw
  work) with no input. Deterministic and machine-independent: `static` motion must be 0, `focus` bounded by the focus
  sites, `all` grows with the avatars on screen. This is the number a budget can gate on.
- **frame CPU time** p50 / p95 / max in ms (render wall time per frame). Machine-dependent, so the variants run
  interleaved in the same JVM and the report gives the ratio to the first variant ("all = 3.4x static"), which is
  comparable across runs on one machine.
- heap used before and after, and the JVM, OS and CPU name (from the existing `PerfProbe.kt`).

Limit, stated in the report: raster Skia on the CPU is not the GPU-backed window (DirectX/OpenGL via Skiko), so the
absolute ms are a CPU cost, not the owner's frame time. Composition and layout cost, the bulk of a Compose frame, are
the same in both. A budget is a named setting (rule 5) whose default gates only on idle work frames; the ms are
reported, not gated.

Cost: one JVM like 3.1, about 15 s per variant. Same isolation as 3.1 (headless AWT, temp state, ephemeral port).

### 3.4 Web check: the wasm client in a headless browser with its own profile

A single-file Java program run by the JDK's source launcher (no build, no dependency, no npm):

```
scripts/testdaemon.sh up --name webcheck              # with INFANTRY_WEB_DIR pointing at the fresh assembleWeb output
java scripts/ui/WebCheck.java --url http://127.0.0.1:<test port>/ --out <dir> [--steps <steps.json>] [--size 1400x860]
scripts/testdaemon.sh down --name webcheck
```

- It starts Edge or Chrome (found at the standard install paths, or `INFANTRY_UI_BROWSER`) with `--headless=new`,
  a fresh temp `--user-data-dir`, `--remote-debugging-port=0`, `--no-first-run --no-default-browser-check
  --disable-extensions --disable-sync`. A different user-data-dir always starts a separate browser process, even when
  the owner's Chrome is open; headless has no window. It reads the DevTools port from the profile's
  DevToolsActivePort file and talks to it over the JDK WebSocket.
- Checks: the page loads; no console error or uncaught exception (Runtime and Log events); the client's WebSocket
  connects (Network events); the canvas is not blank (pixel variance of a screenshot). Output: PNG per shot, console
  log, a pass/fail summary line.
- Steps: the same JSON shape as 3.2, but the wasm client draws everything on one canvas (no DOM nodes), so `click`
  takes coordinates (`{"click": {"x": 120, "y": 48}}`). The coordinates come from the desktop semantics dump of 3.1 at
  the same size: the layout is the shared commonMain code. Input goes through the DevTools Input domain, which
  delivers events inside the headless renderer, never to the OS.
- Safety: it refuses a URL on 8787/8788/8797/8798 or any host but 127.0.0.1, kills the browser by PID and deletes the
  profile at exit (also on Ctrl-C via a shutdown hook), and never attaches to an existing browser.

Cost: `assembleWeb` (minutes, one Gradle build), the test daemon JVM (about 500 MB), the headless browser (300 to
500 MB), about 10 s per check. Uses the installed browser, so nothing is downloaded.

What stays an owner step, with a checklist in the how-to page: GPU-specific rendering, IME and clipboard, Firefox and
Safari, touch on a real phone.

### 3.5 Android: what is possible without the owner's machine

The Android app is a thin shell over `infantryUiCore`; its UI is the commonMain code the desktop render of 3.1
already shows. What only Android can break: the 18 androidMain actuals, the manifest, startup and permissions.

Options:

1. **CI emulator job (recommended).** A GitHub Actions job on a Linux runner with KVM boots a headless emulator
   (`-no-window -no-audio -no-snapshot`), installs the debug APK, launches it, waits, takes `adb exec-out screencap`,
   fails on a crash in logcat, uploads the PNG as an artifact. Off the owner's machine entirely. Cost: about 8 to 12 CI
   minutes per run; run it on nightly and release only, or by hand (workflow_dispatch).
2. Local headless emulator on the owner's PC. No window, but 2 to 4 GB RAM while running, a 1.5 GB system image, a
   hypervisor dependency (WHPX), and the adb server (port 5037) shared with the owner's devices unless
   `ANDROID_ADB_SERVER_PORT` is moved. Rejected as the default; possible for a one-off.
3. JVM-only Android rendering (Robolectric with a screenshot library). Rejected: a new dependency stack for an app
   with no sources of its own; the shared UI is already covered by 3.1.

Until the owner picks, workers check Android by compile (`assembleDebug`) plus the desktop render.

### 3.6 How a worker is told

- A how-to page, docs/wiki/UI-checks.md (written by ticket 7 below), with the four commands, what each proves, and
  the owner-only checklist.
- One line in AGENTS.md section 3 pointing at it, so every agent sees it.
- The frontend and backend role personas (role data, not code; the owner's rule) get one line: "a UI change is checked
  with uiShot (and frameBench when it animates), never with runUi or a browser on the desktop".
- Optional: a project Lua script `ui-check` that runs the shot task through `ih.exec` and posts the PNG path; that is
  project workflow and belongs to the PM's script set, not to the product.

## 4. Safety summary

| Mechanism | Owner windows | Owner input | Ports 8787/8788/8797/8798 | Owner state |
|---|---|---|---|---|
| uiShot / steps | none: offscreen scene, headless AWT | none: test semantics only | ephemeral port, in-process stub daemon | temp dir |
| frameBench | none: offscreen scene, headless AWT | none | ephemeral port | temp dir |
| WebCheck | none: headless browser, own profile | DevTools input inside the headless renderer | test daemon 18900-18999; owner ports refused | temp profile; test daemon temp state |
| Android CI | not on the owner's machine | none | none | none |

All three local mechanisms call the existing PERF-1 guard (`PerfGuard.kt`: `requirePort`, `requireIsolated`) rather
than new checks, and its test grows a case per mechanism.

## 5. Rejected alternatives

| Alternative | Why rejected |
|---|---|
| The owner checks by hand | Blocks every UI ticket on the owner, who is often away or on a phone; leaves no artifact a reviewer can diff; does not measure perf at all. Kept only for the short checklist in 3.4. |
| `runSmoke` / `runUi` with a real window | A window on the owner's desktop steals focus; driving it needs OS-level input, the exact incident the rule forbids. |
| A real window on a hidden virtual desktop or off-screen position | Still an OS window: z-order and focus leak, Robot input still goes to the OS, screen capture reads the owner's screen. |
| Windows Sandbox or a VM per check | Gigabytes of image, minutes to boot, Pro-only feature, a new host dependency for every worker. |
| Playwright, Puppeteer or Selenium | npm or driver downloads (150 to 300 MB of browsers), version churn, and a second toolchain; the DevTools protocol over the JDK does the same few things. |
| The Claude-in-Chrome extension or any tool on the owner's browser | Acts in the owner's real Chrome with their sign-ins: the incident itself. |
| Frame timing in the UI test harness (`mainClock`) | Virtual time: animations are free there (the MASCOT-3 result). |
| Frame timing in a real window (JFR, Skiko FPS counter) | Needs a real window. |
| Pixel-exact baselines for every new screen | `diffTabShots` already proves "nothing changed"; for new UI the worker needs to see it, not a baseline. Baselines stay opt-in. |

## 6. Expensive-to-reverse decisions

- **The step format (3.2, 3.4).** Once workers and scripts write step files, changing the shape breaks them. Keep it
  to the eight verbs above; add verbs, never rename.
- **Gating perf on idle work frames, not ms.** A ms budget would be flaky across machines and CI; reversing a flaky
  gate costs trust. Start report-only for ms.
- **Using the installed browser.** Cheap now; if a browser auto-update breaks headless behaviour, the fix is a pinned
  headless shell download, which is a new dependency.

## 7. Ticket breakdown

Weights follow the ROUTING-1 rubric. Order: 1, 2, 3, 4, 5, 7; 6 waits for owner decision 1. A path in an acceptance
check that does not exist yet is a file the ticket creates.

1. **UI-VERIFY-1a: correct the perf guard's description (weight 1).** AGENTS.md section 6 and the mascot design page
   call `PerfGuardTest` a UI perf budget; it is the PERF-1 isolation guard. Fix both lines; point the MASCOT-3 text at
   the frame bench (ticket 3). Acceptance: `grep -n "UI perf budget" AGENTS.md` prints nothing;
   `grep -n "PerfGuardTest green" docs/wiki/Agent-avatar-mascot-design.md` prints nothing; DocsFreshnessGuardTest green.
2. **UI-VERIFY-2: uiShot runner and steps (weight 3).** A generic offscreen shot runner in the desktop UI test source
   set (3.1, 3.2): scenarios list, PNG plus semantics dump, JSON steps by semantics, headless AWT, isolation through
   the existing PERF-1 guard. Reuse the tab-shots setup (stub daemon, seed data) by extracting it, never by copying;
   existing `*Shots.kt` files are left as they are (no redo). Acceptance:
   `grep -n "uiShot" superiorSoftLibrary/infantryUi/build.gradle.kts` shows the task;
   `grep -n "java.awt.headless" superiorSoftLibrary/infantryUi/build.gradle.kts` shows it on that task;
   `grep -rn "requirePort\|requireIsolated" superiorSoftLibrary/infantryUi/src/test` names the runner;
   a test class proves a steps file with a click and a shot produces the PNG and the semantics file, and that an
   unknown tag fails with a shot of the failure; a run of `uiShot -PuiShot=app` writes a non-blank PNG (paste the
   one-line summary in the report).
3. **UI-VERIFY-3: frameBench (weight 3; after 2).** Real-clock offscreen frame loop (3.3) with interleaved variants,
   idle work frames per second, frame CPU p50/p95/max, ratio to the first variant, JSON report; the gate threshold is
   a named setting whose default gates only idle work frames. Acceptance: `grep -n "frameBench"
   superiorSoftLibrary/infantryUi/build.gradle.kts`; `grep -rn "mainClock" <the bench source>` prints nothing (no
   virtual clock); a test proves a scenario with an infinite animation reports idle work frames above 0 and a static
   one reports 0; LimitsGuardTest green.
4. **UI-VERIFY-3b: re-measure mascot motion (weight 1; after 3).** Run frameBench on a 200-row chat plus 20 sidebar
   agents with motion static, focus and all; put the measured ratio in the `mascot.motion` setting description.
   Acceptance: `grep -rn "mascot.motion" plugins/official` shows a description stating the measured multiple.
5. **UI-VERIFY-4: web check (weight 3).** scripts/ui/WebCheck.java run by the JDK source launcher (3.4): headless
   Edge or Chrome with a temp profile, DevTools over the JDK WebSocket, console and WebSocket checks, non-blank canvas,
   coordinate steps, refusal of owner ports and non-loopback hosts, kill by PID, profile deleted. Started against
   `scripts/testdaemon.sh` with `INFANTRY_WEB_DIR`. Acceptance: `grep -n "user-data-dir" scripts/ui/WebCheck.java`;
   `grep -n "8797" scripts/ui/WebCheck.java` (refusal list); `grep -rn "playwright\|puppeteer\|selenium" scripts`
   prints nothing; a run against a test daemon prints a pass line and writes a PNG (paste it in the report); after the
   run no browser process with the temp profile path is left (`tasklist` check by PID in the report).
6. **UI-VERIFY-5: Android emulator smoke in CI (weight 4; waits for owner decision 1).** A workflow job (nightly,
   release, workflow_dispatch) that boots a headless emulator on a KVM Linux runner, installs the debug APK, launches
   it, fails on a crash in logcat, uploads a screencap. Acceptance: `grep -rn "no-window" .github/workflows` shows the
   job; `grep -rn "screencap" .github/workflows`; the job is absent from the per-push tests workflow
   (`grep -n "emulator" .github/workflows/tests.yml` prints nothing).
7. **UI-VERIFY-6: how-to page and worker pointer (weight 1; after 2, 3, 5).** docs/wiki/UI-checks.md with the
   commands, what each proves, the owner-only checklist; one line in AGENTS.md section 3; the line for the frontend and
   backend personas handed to the PM as role data. Acceptance: `grep -n "UI-checks.md" AGENTS.md docs/wiki/index.md`;
   DocsFreshnessGuardTest green.

## 8. Open owner decisions

1. **Android check.** (a) CI emulator job on nightly and release (recommended: off your machine, about 10 CI minutes
   a run); (b) local headless emulator (2 to 4 GB RAM on your PC); (c) compile plus desktop render only.
2. **Browser for the web check.** (a) the installed Edge or Chrome (recommended: no download); (b) a pinned headless
   browser download (reproducible, about 100 MB, a new dependency).
3. **Perf gate.** (a) gate only on idle work frames, report ms (recommended); (b) also gate ms with a generous budget
   (risk: flaky on slow runners).
4. **Must a UI ticket's report carry a shot?** (a) yes, as a persona rule for frontend workers (recommended: makes
   "unseen UI" visible at review); (b) optional.
