# UI driver: check the real app window on a test instance, with no desktop input

> **legacy-java:** this section describes the Kotlin app. It lives on the `legacy-java` branch. The Rust framework on main is described in [Architecture](Architecture.md).

## Legacy (Kotlin) reference


UI-DRIVER-1 (2026-11-18). A worker starts a TEST instance of the desktop app and drives it through a local API: it
finds nodes in the semantics tree, clicks, types, scrolls, waits and takes screenshots. No window appears on the
desktop, and no OS mouse, keyboard or focus event is ever sent. It builds on the offscreen seam that the tab shots use,
and on the step verbs of the [UI verify design](UI-verify-design.md).

## What runs

- `superiorSoftLibrary/infantryUi/src/test/kotlin/com/superiorsoftllc/infantry/ui/uidriver/UiDriverMain.kt`, Gradle
  task `:superiorSoftLibrary:infantryUi:runUiDriver`. It boots an in-process daemon in a fresh temp state dir, with its
  own repository, saved-commands file and owner credential, and a temp copy of the repository's `plugins/`. It then
  renders the real `App` with every plugin in the desktop Compose UI test scene (Skia raster, offscreen), under
  `-Djava.awt.headless=true`.
- The API (`UiDriverServer`) listens on 127.0.0.1 only, on a port in 18900-18999, with a per-run random token.
- Every action is a Compose semantics action (the click action, text input, scroll-to) or an input event delivered inside the
  offscreen scene (`UiDriverSession`). Nothing reaches the operating system. There is no `java.awt.Robot`.

## Safety fences

| Fence | How |
|---|---|
| Never in a release build | The driver exists only in the infantryUi test source set. `UiDriverReleaseGuardTest` fails if any main source set (client modules, plugins) or the packaging names it, or if a built main class is a driver class. |
| Explicit opt-in | `UiDriverGuard` refuses to start unless `INFANTRY_UI_DRIVER=1` (`-PuiDriver=1`). |
| No window | Headless AWT is required. A real window would throw HeadlessException, so it cannot open, take focus or receive the owner's input. |
| Never the owner's daemon or state | Ports 8787/8788/8797/8798 are refused; only 18900-18999 is accepted. The state dir must be a temp dir outside `~/.infantry`, and `user.home`, `INFANTRY_STATE_DIR` and `INFANTRY_HOME` point at it. The daemon is in-process, so the instance never connects to the owner's daemon. |
| Local and authenticated | The API binds to the loopback address only. Every call needs `Authorization: Bearer <token>`. The token is in the instance file, is never printed, and the wrapper hands it to curl on stdin. |
| No orphans | The instance stops on `quit`, or after `INFANTRY_UI_DRIVER_IDLE_MIN` minutes without a request (default 30). |

## Use it

```
scripts/ui-driver.sh up                       # prints "ready on <port>" (first start compiles the test classes)
scripts/ui-driver.sh query  --instance 18950 --tag-prefix plugin-module-
scripts/ui-driver.sh click  --instance 18950 --desc Configuration
scripts/ui-driver.sh type   --instance 18950 --tag run-form-name --input "Echo"
scripts/ui-driver.sh wait   --instance 18950 --text "Echo" --timeout-ms 10000
scripts/ui-driver.sh shot   --instance 18950 --name run-tab           # PNG in the instance's out dir; prints its path
scripts/ui-driver.sh tree   --instance 18950                          # the semantics tree as text
scripts/ui-driver.sh down   --instance 18950
```

A Lua script calls `scripts/lua/ui-driver.lua` with
`{action="click", instance=18950, json='{"tag":"run-start"}'}`. It runs the same wrapper through `ih.exec`.

The instance takes one Gradle build while it runs (AGENTS.md rule 7). Stop it before you start another build.

## The API

`POST http://127.0.0.1:<port>/ui/<verb>` with a JSON object body. The answer is `{"ok": true, ...}`, or
`{"ok": false, "error": "...", "failShot": "<png of the moment it failed>"}` with status 400.

**Selector** (all given fields must match): `tag`, `tagPrefix`, `text`, `desc`, plus `substring` (for text and desc),
`unmerged` (search the unmerged tree, where tags inside a merged button live) and `index` (which match; default 0).
A plugin's UI half is driven the same way as core UI: by its testTags.

| Verb | Body | Does |
|---|---|---|
| `health` | none | port, pid, size, out dir |
| `tree` | `unmerged` | the semantics tree (plain text with `Accept: text/plain`) |
| `query` | selector, `limit` | `count` and `nodes`: tag, text, editable, desc, role, selected, toggle, disabled, clickable, children, bounds |
| `click` | selector | the node's semantics click action, or a touch inside the scene when it has none (`via`) |
| `type` | selector, `input`, `replace`, `ime` | types `input` into the node (clears it first with `replace`; sends the IME action with `ime`) |
| `key` | `key` (Enter, Escape, Tab, Backspace, Delete, Space, Up, Down, Left, Right, Home, End, PageUp, PageDown), optional selector | presses the key inside the scene, on the node or the top root |
| `scroll` | selector, `toIndex` | scrolls the node into view, or scrolls a list to an index |
| `wait` | selector, `gone`, `count`, `timeoutMs`; or `ms` alone | waits until the node appears, disappears or reaches a count; `ms` alone is a real-time pause that keeps frames coming |
| `shot` | `name`, optional selector | writes `<out>/<name>.png` of the window (or the top dialog), or of one node |
| `quit` | none | stops the instance |

Files: the instance file is `<temp>/infantry-ui-driver/<port>.json` (`port`, `token`, `pid`, `out`, `state`), and the
log is `<temp>/infantry-ui-driver/<port>.log`.

## Limits

- Raster Skia on the CPU, not the GPU window: GPU-specific rendering, IME composition and the clipboard stay owner checks.
- The daemon is an in-process test daemon with the stub provider. Agent replies are scripted, and no real agent CLI runs.
