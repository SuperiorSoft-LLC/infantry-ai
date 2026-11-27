# OS-WINDOW-1: one BattalionOS window manager for every app (design)

Status: design only, for review by the PM and the owner. No code. Ticket `ticket-a2f7b6ef`.
It builds on OS-ARCH-1 (`9df1456b`, design at `6c7fb8b6c`), POPOUT-1 (`cdda35fd`, not started), WINDOW-MIN-1 (`e497fba0`,
not started) and ENGINE-SEAM-AUDIT-1 (`9d03c04f`, not started).

`BOS:` means the BattalionOS repo, read at the local `battalion-os` ref `6165621` with `git show`. Nothing was checked out
there and the owner's running BattalionOS was not touched. Paths without a prefix are in this repo, at `5e715865d`.

## 0. Overlap check (NO REDO rule)

- Done and archived tickets: no window-manager, taskbar-per-window or window-API ticket exists. The archive search for
  "window" finds only unrelated work (UI-DRIVER-1, login windows).
- Git history in both repos:
  - BOS-17 (`a46a5f48e`) and BOS-25 (`b8ced5292`) are the page-in-a-frame message bridge. This design reuses that bridge.
  - BOS-27 (`e609693e4`) gave apps `os_window_*` buttons. Its Infantry part was **reverted** (`9b1dcc41d`, "owner cut it:
    the OS draws its own bar, apps not involved"). This design keeps that line: the OS draws every window's chrome, and an
    app never draws an OS title bar.
- POPOUT-1 and WINDOW-MIN-1 are in the backlog, unbuilt. WINDOW-MIN-1 already says not to build a second window system
  once this one lands. So both of them **move onto** this design (section 5); neither is redone.
- The OS-ARCH-1 SDK tickets (SDK-RS-1, SDK-TS-1) own the SDK packages. The window module is added to their scope. No
  second SDK is planned here.

## 1. Current state

### 1.1 Infantry's in-app floating window (the session pop-in)

| What | Where |
|---|---|
| One component, `ChatPopout`: drag, 8-edge resize, minimize, maximize, close around a `ChatPanel` | `app/shell/src/components/session/chat-popout.tsx` |
| Pure geometry: `clampBox`, `dragBox`, `resizeBox`, MIN 320x240, grab margin 80 px | `app/packages/client/src/popout.ts:1-50` |
| Host for every float: the Assistant box plus `ui.popouts[]` (session ids), z-order as a local `order[]`, `z = 40 + index` | `app/shell/src/components/session/popout-host.tsx:78-92`, `app/shell/src/state/ui-state.tsx:32,145-146` |
| Persistence in `localStorage["infantry.popout.<id>"]`: `{box, minimized, maximized}` | `chat-popout.tsx:13-40,85-91` |
| **Minimize folds the window to its title bar in place.** It does not go to a taskbar. | `chat-popout.tsx:117,140` |
| Maximize fills the app's work area (the parent element), not the screen | `chat-popout.tsx:117` |

The window is only used for chat. Its look (`rounded-xl border shadow-2xl`) is local to Infantry. Nothing outside the app
knows the window exists.

### 1.2 BattalionOS: the desktop window, the app area and the taskbar

| What | Where |
|---|---|
| One OS window, `main`. Decorations are off; the OS draws its own bar (black, 40 px, minimize, maximize, close), a drag region with 8 resize strips, and Windows snap works | `BOS:crates/battalion-desktop/ui/src/TitleBar.tsx:1-99`, `BOS:docs/commands.md:96-104` |
| Apps open **inside** `main` as frames in one app area. `views[]` holds the open apps; `active` is the single view that fills the area; minimize means "not active, frame hidden, never reloaded" | `BOS:ui/src/store.ts:24-27,89-94`, `BOS:ui/src/AppFrames.tsx:1-4,30` |
| There are no floating windows and no z-order: one view at a time | `store.ts:89-94` |
| The taskbar has **one button per app**: running dot, failed `!`, badge text. There are no per-window items | `BOS:ui/src/Taskbar.tsx:2,56-60`, `store.ts:153-158` |
| The badge is per app (`os_badge` / `os_frame_badge`), not per window | `BOS:docs/commands.md:63,74` |
| The page↔OS bridge is `{bos:1,id,cmd,args}` to `window.parent`. Identity is the frame that sent the message (`event.source` plus origin); an id in the message is never trusted. The allow-list has 11 commands | `BOS:ui/src/frames.ts:1-10,40-80`, `BOS:docs/commands.md:76-94` |
| Native per-app windows already exist in code: `open_app_window` makes the label `app-<id>` with its own `data_directory` and origin, "kept for the pop out". The shell no longer calls it (BOS-17) | `BOS:crates/battalion-desktop/src/host.rs:166-191`, `BOS:docs/commands.md:43` |
| Window identity is the Tauri label (`main`, `login`, `app-<id>`), read from the runtime. `access.rs` refuses app A acting on app B | `BOS:src/access.rs:2,46-88`, `BOS:docs/commands.md:13` |
| The permission name `window` is already in the manifest list, and Infantry's `app.json` declares it, but **no command checks it yet** | `BOS:crates/battalion-app-api/src/manifest.rs:166`, `packaging/app.json` |
| Android: each app runs in its own process slot, and the shell keeps the taskbar and Back | `BOS:ui/src/slots.ts:1-4` |
| Versions: tauri 2.12.7, wry 0.57.3, webview2-com 0.39.1 (already linked) | `BOS:Cargo.lock` |
| `webview2.rs` only repairs the runtime lookup; nothing sets a memory level today | `BOS:src/webview2.rs` |

### 1.3 Tauri multi-window in the Infantry standalone app

- There is one main window with decorations off (`app/src-tauri/tauri.conf.json:22`). Its buttons come from
  `host.window` (`app/packages/client/src/host.ts:35-40`, `app/shell/src/host/tauri/index.ts:17-21`,
  `app/shell/src/components/layout/window-controls.tsx`).
- The only extra `WebviewWindow` is the login window (`app/src-tauri/src/login_window.rs:23-50`). There is no pop-out
  window yet; POPOUT-1 would add the first.

### 1.4 How apps are hosted today

- **In a frame of `main`** (desktop default): a page app or a process app's page, served on the app's own page port. It
  talks to the OS by messages (`app/shell/src/host/battalion/frame.ts`).
- **In its own `app-<id>` window** (the code is kept, not used): the page calls `invoke` directly, and the label is its
  identity (`app/shell/src/host/battalion/index.ts`).
- **Android slot**: its own process, with the shell's taskbar.
- **Standalone or browser**: there is no OS. Infantry draws its own bar and its floats (`host.window`, `ChatPopout`).

The gap: windows are an app-local idea, so (a) the taskbar cannot show them, (b) every app styles its own, and (c) the OS
cannot manage their memory or z-order.

## 2. The API

### 2.1 Options considered

| Option | For | Against | Verdict |
|---|---|---|---|
| **A. The OS draws every window, and the content is always a separate frame of the app at a route** | One chrome; the OS owns z-order, snap and taskbar | Every in-app float becomes a separate document with its own JS heap and daemon socket. The Infantry chat pop-in would lose its shared in-page state and cost about one more app bundle per window. It also does not work standalone | Too costly as the only kind |
| **B. Apps draw windows with a shared SDK chrome component; the OS knows nothing** | Cheapest; shared state stays | No taskbar, no OS z-order, and styling is only as consistent as each app's package version. This is today's situation, plus a shared component | Misses the goal |
| **C. One window model, three kinds, picked per window** (chosen) | Each kind is the cheapest correct one for its job. One API, one chrome component, one taskbar | Three renderers to keep consistent. This is solved by having one chrome component and one geometry module, used by all three | **Chosen** |

### 2.2 The model

```
Window {
  wid:    "w-<app>-<n>"        // given by the OS; scoped to the app (app A can never name B's)
  app:    "<app id>"           // from the caller's identity, never from an argument
  key:    "session.e7ecdc1d"   // chosen by the app; stable; the unit of focus-instead-of-duplicate and of persistence
  kind:   "inline" | "float" | "native"
  title, icon?, badge?: {count?|text?}, attention?: bool
  state:  "normal" | "minimized" | "maximized" | { snap: "left"|"right"|"tl"|"tr"|"bl"|"br" }
  bounds: { x, y, w, h }       // in the coordinate space of its surface (below)
  z:      number               // OS-owned stack order within the surface
  route?: "/session/e7ecdc1d"  // float/native only: the app page path the content frame loads
}
```

| Kind | Who draws the chrome | Content | Surface (bounds, z) | Use |
|---|---|---|---|---|
| `inline` | The app, with the **OS chrome component** (`WindowFrame`, 3.2) | The app's own DOM; state is shared | The app's view (its frame) | Floats that belong to one app's screen: the Infantry chat pop-in, the Assistant box |
| `float` | The OS shell | A frame of the app at `route`. It is same-origin and same-site, so it shares the app page's renderer process | The OS work area of `main`, above app views | A window that must stay visible while another app or the desktop is shown. **Phase 3, only when an app needs it** |
| `native` | The OS: a small OS host page with the bar, plus the app frame | A frame of the app at `route` | The screen (a Tauri window labelled `win-<app>-<n>`) | POPOUT-1: its own OS window, several monitors |

A minimized window of any kind is a **taskbar item**, not a folded bar. The OS restores it at its previous bounds and state.
On a phone, `float` and `native` open as a slot view (full screen). The `open` result says which kind was actually used.

### 2.3 Frame commands (page → OS)

These are added to the existing bridge allow-list (`BOS:ui/src/frames.ts:10`). The identity rule is unchanged. A page in
its own `app-<id>` or `win-*` window calls the same names with `invoke`, and its label is its identity. Every command needs
the `window` permission (already in the list, and already declared by Infantry), checked at each call as in
`frame_calls.rs`.

| Command | Args | Result | Notes |
|---|---|---|---|
| `os_win_open` | `key, kind, title, icon?, route?, bounds?, state?` | `{wid, kind, state, bounds, restored}` | If `key` is already open, the call focuses that window and returns it (`restored:false`). If the OS saved state for this `(app,key)`, it uses that over `bounds`/`state` (`restored:true`). `route` must be a path on the app's own origin |
| `os_win_close` | `wid` | nothing | Removes the window and its taskbar item. Saved geometry is kept |
| `os_win_minimize` / `os_win_restore` / `os_win_maximize` / `os_win_focus` | `wid` | `{state}` | Focus raises the window to the top of its surface and restores it if minimized |
| `os_win_snap` | `wid, zone` | `{state, bounds}` | Zones: halves and quarters of the surface. For `native`, Windows snap works anyway (drag region) |
| `os_win_set` | `wid, title?, icon?, badge?, attention?, bounds?` | nothing | `bounds` is only for `inline` (the app moved it; the OS saves it) |
| `os_win_list` | | `Window[]` | The caller's own windows only |

Events (OS → page, `{bos:1, event:"os_win", ...}`, sent only to the owning app's frames):
`{wid, change:"state", state, bounds}` (for example, a taskbar click restored it), `{wid, change:"closed"}` (the taskbar
close, the app stopped, or a native window was closed), and `{wid, change:"focus", z}`.
For `inline`, **the OS is the source of truth for state and the app renders it**: a taskbar click sends `state` and the
app shows the window. This removes today's local `order[]` and `minimized` flags.

Main-only shell commands (the same pattern as `os_frame_*`) carry these out for frames: `os_frame_win_*` with the app id
taken from the frame.

### 2.4 SDK calls (the OS-ARCH-1 SDKs; types generated from one schema)

Rust (`battalion-sdk::windows`, SDK-RS-1): serde types `WindowSpec`, `WindowKind`, `WindowState`, `SnapZone`, `WinEvent`,
plus the JSON schema `window.schema.json` next to `app.schema.json`. This crate is where the TS types come from.

TS (`@battalion/app-sdk`, SDK-TS-1):

```ts
const w = await windows.open({ key: `session.${id}`, kind: "inline", title, icon });
w.on("state", (s) => render(s));     // the OS (taskbar) changed it
w.minimize(); w.restore(); w.maximize(); w.snap("left"); w.focus();
w.set({ badge: { count: 2 }, attention: true });
w.close();
windows.list();
```

It has two backends behind one interface, picked at start:
- **`osBackend`** (under BattalionOS): the frame commands above.
- **`localBackend`** (standalone Tauri, browser): an in-page window manager with the same z-order, snap, persistence
  (`localStorage`) and chrome, plus a **dock strip** along the bottom edge as the taskbar. It supports `inline` only;
  `float` falls back to `inline`. `native` returns `kind:"inline"`, so the app still works (decision 4.4).

Infantry reaches it through its `Host` seam (`app/packages/client/src/host.ts`). A new `host.windows` replaces the ad-hoc
`ui.popouts` and `order[]`, so the app never imports a platform API directly (HC-1, ENGINE-SEAM-AUDIT-1).

### 2.5 Taskbar integration

- The app button stays. Its open windows group under it: the button shows a count, and a click with more than one window
  opens a list (title, icon, badge, close X). Each **minimized** window is always reachable from that list. The setting
  `bos.taskbar.groupWindows` (default `true`; `false` = one taskbar button per window, the Windows "never combine" mode).
- Per-window badge and attention. The app button shows the app badge (`os_badge`, unchanged) or, if there is none, the
  sum of its window counts; attention pulses the button.
- Native windows also get their own Windows taskbar button (their own top-level window) with the overlay badge, the same
  way `os_badge` does it today.

### 2.6 State persistence

- The OS stores `(app, key) → {kind, bounds, state, monitor?}` in `<root>/state/windows/<app>.json`. A write is debounced
  (setting `bos.windows.saveDebounceMs`, default 500) and done on a blocking thread. Removing the app deletes the file;
  an update keeps it.
- On restore, the bounds are clamped to the current surface or monitor (a generalised `clampBox`), so a window is never
  lost off-screen.
- The setting `bos.windows.restoreOnStart` (default `false`, the cheapest choice; POPOUT-1 asked for it to be optional)
  reopens native and float windows when the app starts. Inline windows are the app's to reopen; the OS keeps only their
  geometry.

### 2.7 Permissions and limits

- `window`: needed for every `os_win_*` call. A page without it gets the refusal by name, as BOS-25 does.
- There is **no cap** on the number of windows (POPOUT-1, the owner's word). Instead, `os_win_list` for native windows
  carries `memoryMb` where the runtime gives it, and the taskbar list shows it (POPOUT-1: "show each window's memory").
  If a cap is ever wanted, it is the setting `bos.windows.max` (default unset = no cap), per rule 5.
- An app may only name its own `wid`s (an `access.rs` rule, like `authorize_app`).

## 3. Styling: OS-owned chrome

- **One component, `WindowFrame`**, lives in the OS kit (`BOS:crates/battalion-desktop/ui/kit/blocks/window-frame.tsx`):
  the title, an icon slot, minimize, maximize/restore and close, a drag area, 8 resize strips and the snap preview. The
  OS shell draws `float` and `native` chrome with it. It is published as `@battalion/app-sdk/chrome` for `inline` and the
  local backend. **One source file, three users.** The geometry (`clampBox`/`dragBox`/`resizeBox` plus the snap zones)
  moves into the same package from `app/packages/client/src/popout.ts`; Infantry then imports it and keeps no copy.
- **Theme tokens** are CSS variables owned by the OS:
  `--bos-win-bg`, `--bos-win-fg`, `--bos-win-border`, `--bos-win-radius`, `--bos-win-shadow`, `--bos-win-title-h`,
  `--bos-win-title-bg`, `--bos-win-title-bg-inactive`, `--bos-win-btn-hover`, `--bos-win-btn-danger`,
  `--bos-win-focus-ring`, `--bos-win-snap-preview`.
  The defaults derive from the OS bar's look (`TitleBar.tsx`), scaled to a 32 px title. The OS pushes the current set to
  every app frame (`{bos:1,event:"os_theme",tokens}`) on start and on a light/dark change, so inline chrome follows the OS
  theme. The local backend ships the same defaults.
- **Apps style only their content.** `WindowFrame` takes `title`, `icon`, `badge` and `children`, with no `className` or
  style props on the chrome. That makes consistency a property of the API, not a review rule. It matches the owner's
  BOS-27 cut: the OS draws bars, and apps are not involved.

## 4. Threads, memory, engine seam

- **Main thread.** Every native window create, close or state change goes through Tauri's main-thread event loop
  (`AppHandle::run_on_main_thread`, or async commands, never a sync command that builds a window: that deadlocks on
  Windows). The window table is owned by one tokio task (a `WindowManager` actor behind an `mpsc`). Commands send to it;
  it calls the main thread only for the Tauri step. It never blocks the main thread; persistence runs on
  `spawn_blocking`.
- **Low-memory mode.** When a native window is minimized or hidden for `bos.windows.lowMemoryAfterSec` (default 30; `0` =
  off), the OS sets WebView2
  `ICoreWebView2_19::put_MemoryUsageTargetLevel(COREWEBVIEW2_MEMORY_USAGE_TARGET_LEVEL_LOW)` through
  `webview.with_webview(..)` → `controller().CoreWebView2()?.cast::<ICoreWebView2_19>()`. webview2-com 0.39.1 is already
  in BOS's lock file. On restore or focus it sets `NORMAL`.
  - `LOW` keeps scripts running, so the page's daemon socket stays up.
  - `TrySuspend` (ICoreWebView2_3) is **not** used by default: it stops timers and sockets. It is left as a later
    opt-in setting.
  - `inline` and `float` windows live in `main`'s webview, so they share `main`'s level. Their hidden frames are
    already "hidden, never reloaded".
- **Engine seam** (ENGINE-SEAM-AUDIT-1). The native backend sits behind a Rust trait inside battalion-desktop:
  ```rust
  trait NativeWindows {
      fn create(&self, spec: &NativeSpec) -> Result<NativeId, String>;   // on the main thread
      fn close(&self, id: NativeId);
      fn set_state(&self, id: NativeId, s: WindowState);
      fn start_drag(&self, id: NativeId);
      fn memory_level(&self) -> Option<&dyn MemoryLevel>;               // None on engines without it
  }
  ```
  The Tauri/wry implementation is the only one today. A Verso or Servo runtime implements the same trait. The WM, the
  taskbar and the frame commands never see Tauri.

## 5. Migration (phased; tickets LISTED, not filed)

| # | Ticket | Repo | What | Depends on |
|---|---|---|---|---|
| 1 | WM-1 window model | BOS | The `WindowManager` actor, window table, `(app,key)` persistence, and the pure geometry and snap module (moved from `app/packages/client/src/popout.ts`). `docs/commands.md` section; `tests/commands_frozen.rs` updated | none |
| 2 | WM-2 frame commands | BOS | `os_win_*` on the bridge allow-list (`frames.ts:10`) and as `invoke` commands, `os_frame_win_*` main-only, `os_win` events, the `window` permission check, the access rule | 1 |
| 3 | WM-3 taskbar | BOS | Per-window items grouped under the app button, the window list, per-window badges and attention, `bos.taskbar.groupWindows` | 1 |
| 4 | WM-4 chrome | BOS | `WindowFrame` plus the `--bos-win-*` tokens in the kit, the `os_theme` event, published as `@battalion/app-sdk/chrome` (inside SDK-TS-1's package) | none |
| 5 | WM-5 SDK windows | BOS | `battalion-sdk::windows` types and schema (into SDK-RS-1); `windows` client with `osBackend` and `localBackend` plus the dock strip (into SDK-TS-1) | 2, 4 |
| 6 | **WINDOW-MIN-1, retargeted** | IAI | `ChatPopout` and the Assistant box become `inline` windows through `host.windows`: `WindowFrame` chrome, minimize goes to the BOS taskbar (or the dock strip standalone), and restore keeps the bounds. `ui.popouts` and `order[]` read from the WM. The fold-to-title-bar minimize **is replaced**: WINDOW-MIN-1 names that change. Its tests as written | 3, 5 |
| 7 | WM-6 native kind | BOS | `win-<app>-<n>` Tauri windows: the OS host page (`window.html`, bar plus one app frame, reusing `frames.ts` identity), the `NativeWindows` trait, main-thread creation, per-monitor persistence, `bos.windows.restoreOnStart`. Native windows close when their app stops | 1, 2, 4 |
| 8 | WM-7 low memory | BOS | `MemoryUsageTargetLevel` LOW/NORMAL on hide/restore, `bos.windows.lowMemoryAfterSec`, `memoryMb` in `os_win_list` | 7 |
| 9 | **POPOUT-1, on WM-6** | IAI | The "Pop out" action on a session (card menu and header) opens `os_win_open {kind:"native", key:"session.<id>", route:"/session/<id>"}`. The shell renders the route with no sidebar or headers. Focus-instead-of-duplicate comes from `key`. Hidden in the browser, on Android and in the standalone app (4.4) | 7 |
| 10 | WM-8 float kind (later) | BOS | OS-drawn floats over the work area of `main`. Filed only when an app needs a window to outlive its view | 2, 4 |

Order: 1 → (2, 3, 4 in parallel) → 5 → 6 (the owner's visible ask, WINDOW-MIN-1) → 7 → 8 → 9. Numbers 1 to 6 give the
owner minimize-to-taskbar with consistent chrome. Numbers 7 to 9 give pop-outs.

## 6. Decisions that are expensive to reverse

1. **The command, event and permission names** (`os_win_*`, the `os_win` event, `window`). The commands doc is frozen v1
   (`tests/commands_frozen.rs`); third-party apps and agent-built local apps will code against them.
2. **`key` as the app's window identity and persistence unit.** Saved state on users' machines is keyed by it, and
   focus-instead-of-duplicate depends on it. Changing it later loses saved layouts and breaks dedupe.
3. **The OS owns the chrome; `WindowFrame` has no style props.** Once apps ship, opening up styling is easy, but taking
   it back breaks every app that used it. The owner's BOS-27 cut already points this way.
4. **Native windows only under BattalionOS** (the standalone Infantry app gets `inline` only). The alternative was a
   second native implementation in `app/src-tauri` for POPOUT-1. That is cheap to add later; building it now would be
   two window systems, which WINDOW-MIN-1 forbids.
5. **Native window = OS host page plus app frame** (not Tauri's multi-webview windows, which are behind `unstable`). This
   reuses the proven frame identity and bridge. Switching later changes how a native page reaches the OS (messages
   versus `invoke`), which every app's SDK backend would feel.
6. **The OS is the source of truth for `inline` window state.** Apps render what the OS says. Reversing it would
   re-split the state that WM-1 unifies.

## 7. Not decided here (test later)

- The real memory cost per native window, and how much `LOW` saves on Infantry's page. Measure this in WM-7 on the
  owner's machine profile (test daemons only).
- Whether `float` is ever needed. It is kept out of the build plan until an app asks for it.
- BattalionOS was read at local `battalion-os` `6165621` (`origin/battalion-os` was `bf80d4c`, not fetched). Newer
  commits are not covered.
