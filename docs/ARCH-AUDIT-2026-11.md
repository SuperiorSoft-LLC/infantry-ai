# Duplicate and ad-hoc implementation audit (ARCH-AUDIT-1, 2026-11-07)

Design record, dated. The code wins over this page. Measured on commit `53cadd09` (branch
`ticket/f70e1334-arch-audit-1-audit-for-duplicate-ad-hoc`, cut from `feature-agenthq-mvp`). Read-only audit: no code
changed.

Owner, 2026-11-07: "these agents are just creating their own shit, I know some of this code is not properly
structured". That day's evidence: a full-feed `SelectionContainer` that crashed on icon clicks, a plugin drawing its own
`Popup` tooltip, Voice Input drawing its own red error text, several unrelated notification paths, usage refusals
bolted on per feature. Each agent solved its own problem locally instead of using, or adding, one shared seam.

How to read the tables: **Seam** is the one implementation everything should go through (existing, or "to add" with
the place it belongs). **Copies** are the other implementations, as `file:line`. **Size** is the migration estimate
(S under 10 call sites / one module, M 10-40 sites or 2-4 modules, L more). Each concern has one backlog ticket,
listed in the last section, with the guard test that stops a relapse.

Path shorthand in the tables: `SSL/` = `superiorSoftLibrary/`, `PO/` = `plugins/official/`, `~` =
`src/main/kotlin/com/superiorsoftllc/infantry`. Main sources only (no `build/`, no tests).

## Summary

| # | Concern | Seam today | Copies | Size | Ticket |
|---|---|---|---|---|---|
| 1 | Notifications, toasts, dialogs | `ToastService`/`ToastHost` (unused by production code), `AhqAlertDialog` | ~14 notice surfaces, 13 raw `AlertDialog`, 2 `Dialog`, 2 scrims | L | NOTIFY-CORE-1 2859638f, then NOTIFY-MIGRATE-1 7664e501 |
| 2 | Tooltips, popups, hover | `IconActionTooltipBox` (icon-shaped only) | 6 raw tooltips, 8 hover-reveal, 5 raw menus | S-M | TOOLTIP-SEAM-1 d6232f36 |
| 3a | Error display (UI) | none (`Palette.Danger`, `PluginFaults`, `GuardedBlock` falls back to nothing) | 94 red texts in 53 files, 5 error cards | L (mechanical) | ERROR-UI-SEAM-1 e5ea1600 |
| 3b | Error handling (daemon) | `PluginGuard` + a second harness fault path | 276 dropped `runCatching`, 4 dropped `Throwable` | L | FAULT-LOG-1 a279d927 |
| 4 | Settings | `DaemonApi.settings` (prefix list), `config`, `HarnessHost.setting`, UI `PluginSettings` | ~11 plugin files with getenv/properties | M | PLUGIN-SETTINGS-2 2c50624d |
| 5 | HTTP and retry | none on the daemon; `createPlatformHttpClient` on the UI | 18 client sites, 10 retry loops | L | HTTP-SEAM-1 d2de18a0 (with CORE-PROGRESS-DOWNLOAD-1 1775ad12) |
| 6 | Process spawning | `ProcessRunner`, `HarnessHost.start`, `BoundedCommand`, `GitCli`; no start for daemon plugins | 34 `ProcessBuilder` in 25 files, 9 tree kills | L | PROC-SEAM-1 338cc5b5 |
| 7 | Paths, state dir, atomic writes | `stateDir()`, `DaemonApi.stateDir`, `ProjectFiles`; no atomic write | 8 state-dir copies, 22 atomic writes | M-L | FILES-SEAM-1 8e5fefc7 |
| 8 | Clickables, cursor | `Clickables.kt` + `HandCursorGuardTest` | 3 bypasses, 4 star imports, guard holes | S | CLICK-GUARD-2 9737a388 |
| 9 | Selection, chat text | `ChatMarkdownEngine`, `LocalChatMarkdown` | whole-feed `SelectionContainer`, 1 seam bypass, 1 stripper copy | S-M | CHAT-SELECT-1 caaf1e9e |
| 10 | Logging | `DaemonApi.log`, `HarnessHost.log` (no levels) | 319 `println`, 27 stderr defaults | M | LOG-SEAM-1 b5688776 |
| 11 | JSON helpers | none used broadly | 215 `Json {}`, ~40 helper copies, 2 whole-file copies | M | JSON-KIT-1 38ec058e |
| 12 | Caching | none | 11 TTL caches, 7 unbounded | S-M | CACHE-SEAM-1 76613922 |
| 13 | Hashing | none | 24 SHA-256 sites | S | FILES-SEAM-1 8e5fefc7 |
| - | Progress, downloads | none | 8 spinners, 5 animations, 3 downloaders | L | CORE-PROGRESS-DOWNLOAD-1 1775ad12 |
| - | Wire contract twice | none (no parity test) | `Protocol.kt` vs `WireProtocol.kt` | M-L | WIRE-PARITY-1 0ddbd10e |
| - | Features and dead code in core | rules 1-2; the guards miss these | 7 places | M | CORE-LAYER-1 96b440ae |
| - | Files over 2,000 lines | none | 7 files | per split | FILE-SIZE-GUARD-1 98b605ee (+ AI-FIX-1/2/3/9) |

In the UI sections `uiCore/` = `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/`.

## 1. User notifications, toasts, banners, dialogs

NOTIFY-CORE-1 (2859638f, in progress) designs the bus and presenter; this section is its inventory input, not a second
design.

**Seams today.**
- Toasts: `ToastService` / `LocalToast` / `NoToastService` (`uiCore/ToastService.kt:19`, `:44`, `:38`), drawn by
  `ToastHost` / `Toast` (`uiCore/ToastHost.kt:78`, `:96`) with `toastColour` (`:58`), shell side `ToastContext`
  (`uiCore/shell/ShellContext.kt:949`) and `uiCore/shell/blocks/ShellToast.kt:30`; `Controller.postToast`
  (`uiCore/net/Controller.kt:336`). **`LocalToast.current` is read by no production code**: toasts are posted only from
  `Model.kt:1645`, `Model.kt:1989` and the plugin `Services.NOTIFICATIONS` bridge (`DesktopPlugins.kt:153`). The seam
  exists and nobody uses it.
- Modals: `AhqAlertDialog`, `ModalBackdrop`, `RegisterModal` (`uiCore/ModalBlur.kt:101`, `:90`, `:70`); 15 callers.
- OS notifications: `Notifier` (`PO/Infantry_Notify/src/Notifier.kt:19`) with Desktop/Android/Web notifiers.

**Copies.**

| Where | What |
|---|---|
| `PO/Infantry_Chat/src/ChatSurface.kt:326`, `:343` | `NoticeStrip`: the toast look copied, own 8 s timer, own copy of `toastColour` |
| `SSL/infantryUi/.../UpdatePrompt.kt:159` | `UpdateCard`, its own bottom-right card |
| `uiCore/voice/VoiceUi.kt:66-70` | own spoken-alert box, own 8 s clear |
| `PO/Infantry_Board/src/Board.kt:822` | drop note in red, own 8 s dismiss |
| `PO/Infantry_Approvals/src/QuestionPill.kt:72`, `:99` | own pill and "question gone" card |
| `SSL/infantryUi/.../InfantryWindow.kt:115` | `HostErrorBanner`, own top strip |
| `uiCore/shell/blocks/ShellDropIn.kt:269` | `SafeModeBanner` |
| `PO/Infantry_Settings/src/PluginsScreen.kt:147`, `:218`, `:276` | restart banner, `note` text, `FailedNotice` |
| `PO/Infantry_Notify/src/NotifyModule.kt:146`, `uiCore/sound/IncomingCommsSoundUi.kt:77`, `uiCore/voice/plugin/PluginsUi.kt:48` | local status text |
| `SSL/infantryUi/.../WindowActivation.kt:103` | Swing `JOptionPane.showMessageDialog` |
| `PO/Infantry_Updater/androidSrc/AndroidSelfUpdate.kt:148` | Android `Toast.makeText` |
| 13 raw `AlertDialog` (no blur registration): `PO/Infantry_Board/src/EpicMoveUi.kt:153`, `ArchiveView.kt:62`, `AgentTaskActions.kt:93`, `:102`, `:111`, `:128`; `PO/Infantry_Settings/src/PluginsScreen.kt:520`, `PluginConsentDialog.kt:56`; `uiCore/ServerSwitcherUi.kt:112`; `uiCore/shell/blocks/ShellSwitchError.kt:19`, `ShellDropIn.kt:192`; `SSL/infantryUi/.../UpdatePrompt.kt:65`, `:88` | dialogs that skip `AhqAlertDialog` |
| `PO/Infantry_Agents/src/Sidebar.kt:518`; `androidMain/.../shell/ConnectScreen.kt:318` | raw `Dialog` |
| `uiCore/EditorModal.kt:65` (5 callers: Epics, Notes, Prompts, Roles, Skills); `uiCore/shell/blocks/ShellCompactDrawer.kt:46` | own scrims, no `RegisterModal` |

About 14 notice surfaces, 13 raw `AlertDialog`, 2 raw `Dialog`, 2 own scrims. **Seam:** NOTIFY-CORE-1's bus for
notices; `AhqAlertDialog` / `ModalBackdrop` for every dialog and scrim. Size L (NOTIFY-CORE-1 ships 2-3 migrations;
the rest is the follow-up ticket).

## 2. Tooltips, popups and hover hints

**Seams.** `IconActionTooltipBox` (`uiCore/IconAction.kt:128`: persistent, opens on hover enter/exit and focus),
`WritePolicyTooltip` (`:111`), `iconActionTooltip` (`:57`); menus: `InfantryDropdownMenu`
(`uiCore/InfantryDropdownMenu.kt:17`, 13 callers). No `Popup(`, `TooltipArea`, `CursorDropdownMenu` or `RichTooltip` is
left in the tree today (the plugin `Popup` tooltip seen on 2026-11-07 is gone), but there is no general host tooltip for
non-icon content: `IconActionTooltipBox` is shaped around an icon action. READALOUD-HOVER-SHIFT-1 (b087b2f0) needs one.

**Copies.**

| Where | What |
|---|---|
| `uiCore/TicketTag.kt:56`, `:79`; `uiCore/ExecutorPicker.kt:106`; `uiCore/EffortViews.kt:74`; `uiCore/ContextLimitViews.kt:75`; `PO/Infantry_Chat/src/ChatSurface.kt:309` | raw `TooltipBox` + `PlainTooltip`, not persistent, no hover handling |
| `PO/Infantry_Pace/src/PaceChart.kt:158`, `:196`, `:245` | hand-built hover readout from pointer events |
| `PO/Infantry_Agents/src/Sidebar.kt:281`, `PO/Infantry_Chat/src/ChatSurface.kt:569`, `PO/Infantry_ChatFeed/src/MessageActions.kt:123`, `PO/Infantry_Header/src/TitleBar.kt:379`, `uiCore/SearchableSelect.kt:141`, `uiCore/ScrollCue.kt:83`, `uiCore/FloatingChip.kt:48`, `uiCore/ToastHost.kt:100` | hover-reveal by hand (`hoverable` + `collectIsHoveredAsState`) |
| `uiCore/ConfigDialog.kt:735`, `:767`, `:1279`, `:1355`, `:1537` | raw `ExposedDropdownMenu`, styled by hand |

**Seam to have.** `HostTooltip(text | content, anchor)` in `uiCore` (one tooltip host: placement that never shifts
layout, delay and persistence settings, hover and focus), with `IconActionTooltipBox` rebuilt on it; a
`Modifier.hoverReveal()` for the hover-to-show pattern. Size S-M (6 tooltips, 8 hover sites).

## 3a. Error display in the UI

**Seams.** No shared error text or error card exists. Colour: `Palette.Danger` (`uiCore/Theme.kt:36`). Fault
containment: `PluginFaults` (`uiCore/plugin/PluginFaults.kt:31`, `hostError` `:38`, `handle` `:76`,
`FaultedPluginsList` `:91`), `GuardedBlock` / `probeComposition` (`uiCore/block/layout/OverrideGuard.kt:68`, `:95`, used by
`Resolver.kt:104`, `:209` and `plugin/TabFactory.kt:1062`; PLUGIN-CRASH-CONTAIN-1 ea8b8aa5). A faulted section's
fallback is `{}`: it draws nothing, so the user cannot tell a crash from an empty panel. Window:
`NoDialogExceptionHandler` (`SSL/infantryUi/.../InfantryWindow.kt:108`).

**Copies.** 94 `Text(..., color = Palette.Danger)` in 53 files; about 40 are the same
`error?.let { Text(it, color = Palette.Danger, fontSize = 11.sp) }`: `PO/Infantry_Epics/.../EpicsPane.kt:114`,
`PO/Infantry_Usage/.../UsagePacingSection.kt:245`, `PO/Infantry_Pods/.../PodsPane.kt:95`, `PO/Infantry_Notes/.../MemoryPane.kt:358`,
`PO/Infantry_Skills/.../SkillsPane.kt:114`, `PO/Infantry_Chat/.../NewAgentDialog.kt:215`, `ChatAttachmentViews.kt:82`,
`PO/Infantry_Triggers/.../Triggers.kt:249`, `PO/Infantry_Settings/.../PluginConsentDialog.kt:74`,
`PO/Infantry_Header/.../ProjectPicker.kt:251`, `:324`, `:396`, `:421`, `:465`, `:503`, `PO/Infantry_Git/.../ProjectPipelineSection.kt:95`,
`ProjectGitPane.kt:184`, `PO/Infantry_Run/.../RunPane.kt:658`, `PO/Infantry_Prompts/.../PromptsPane.kt:106`,
`PO/Infantry_Profiles/.../ProfilesPane.kt:181`, `PO/Infantry_Board/.../TicketCommentsPanel.kt:59`, `Board.kt:1020`, `:1065`,
`ArchiveView.kt:72`, `PO/Infantry_Roles/.../RoleManager.kt:319`, `TraitsEditor.kt:81`, `uiCore/AboutSection.kt:62`,
`uiCore/ConfigDialog.kt:1459`, `uiCore/EditorModal.kt:86`, `uiCore/LayerSkillsPanel.kt:102`, `uiCore/pair/PairDevice.kt:169`,
`uiCore/plugin/ModulesSection.kt:308`, `uiCore/ServerSwitcherUi.kt:139`, `uiCore/SwitchAgentPicker.kt:155`,
`uiCore/TicketImages.kt:213`, `uiCore/plugin/config/GeneratedConfig.kt:144`, `uiCore/shell/blocks/ShellDropIn.kt:164`, `:212`,
`uiCore/voice/plugin/PluginsUi.kt:103`. Material's `colorScheme.error` instead of the palette:
`androidMain/.../ConnectScreen.kt:268`, `:315`, `SSL/infantryUi/.../DaemonRefusalCard.kt:38`. Error cards:
`DaemonRefusalCard.kt:32`, `ShellSwitchError.kt:19`, `PluginsScreen.kt:276` (`FailedNotice`), `AgentTaskActions.kt:128`,
`InfantryWindow.kt:115`. Engine guards beside `PluginFaults`: `jvmMain/.../markdown/MarkdownEnginePluginLoader.kt:41`
(`GuardedMarkdownEngine`) and `uiCore/model/ChatMarkdownEngine.kt:55`.

**Seam to have.** `InlineError(message, retry?)` and `ErrorCard(title, detail, actions)` in `uiCore` (one look, one
place to change it, the Retry action), and a visible "this panel failed: <plugin>, Retry" fallback in `GuardedBlock`
instead of `{}`. Transient errors go to the NOTIFY-CORE-1 bus, field-level errors to `InlineError`. Size L by count but
mechanical (53 files).

## 8. Clickable and cursor handling

**Seam.** `uiCore/Clickables.kt` (`handCursor` `:39`, `clickable` `:44`, `Button` `:65`, `OutlinedButton` `:78`,
`TextButton` `:91`, `DropdownMenuItem` `:104`, `Checkbox` `:114`, `RadioButton` `:125`, `Switch` `:136`, `FilterChip`
`:147`, `dragHandleCursor` `:168`), guarded by `HandCursorGuardTest` (infantryUiCore jvmTest): it scans uiCore
commonMain/jvmMain/androidMain under `ui`, infantryWeb wasmJsMain and all of `plugins/official`; bans the raw imports and
fully qualified calls; ratchets raw counts (Text 1169, DropdownMenu 10, OutlinedTextField 105, ...).

**Holes in the guard.**
- Not scanned: `SSL/infantryUi/src/main` (`DaemonRefusalCard.kt:10` imports `material3.Button`, a real bypass;
  `InfantryWindow.kt:220` raw `pointerHoverIcon`), `plugins/community` (`Acme_Echo/.../EchoPlugin.kt:8`, `:55`,
  `Acme_Hello/.../HelloPlugin.kt:10`, `:103`: raw `OutlinedButton`), `plugins/demo`, `plugins/examples`, `plugins/_template`.
- Star imports pass the import regex: `import androidx.compose.material3.*` with `com.superiorsoftllc.infantry.ui.*` in
  `PO/Infantry_Board/src/TicketCommentsPanel.kt:9`, `EpicMoveUi.kt:12`, `ArchiveView.kt:8`, `AgentTaskActions.kt:6`.
  Plugin packages are not `...ui`, so which `TextButton` they get is overload luck.
- The `pointerHoverIcon` check uses a non-recursive `listFiles` (`HandCursorGuardTest.kt:105`): uiCore subpackages and
  jvmMain/androidMain are unchecked.
- No wrapper for `IconButton`, `combinedClickable`, `selectable`, `toggleable`; patched by hand with `.handCursor()` at
  `PO/Infantry_Usage/src/UsagePacingSection.kt:259`, `PO/Infantry_Board/src/EpicMoveUi.kt:93`, `uiCore/ConfigPolicySection.kt:73`,
  `uiCore/ConfigDialog.kt:1511`, `uiCore/plugin/config/GeneratedConfig.kt:190`. Taps via `detectTapGestures` with no
  cursor: `TitleBar.kt:137`, `MessageActions.kt:125`, scrims `DiffModal.kt:109`, `BoardModal.kt:83`, `EditorModal.kt:67`,
  `ShellCompactDrawer.kt:46`.

**Seam to have.** The same `Clickables.kt`, plus `IconButton`, `combinedClickable`, `selectable`, `toggleable` wrappers,
and the guard widened to every UI source set and every plugin folder, star imports of `material3.*` banned. Size S.

## 9. Selection and text rendering in chat

**Seams.** `ChatMarkdownEngine` (`uiCore/model/ChatMarkdownEngine.kt:13`; `BuiltInChatMarkdownEngine` `:27`,
`parseChatMarkdownSafely` `:52`, `ChatMarkdownRegistry` `:64`, `ChatMarkdown` `:83`); parser `uiCore/model/ChatMarkdown.kt:213`;
renderers `uiCore/ChatBlockRenderers.kt` (`LocalChatMarkdown` `:61`, `DefaultChatBlockRenderer` `:64`); spans
`uiCore/ChatSpans.kt:33`. Provided at `uiCore/App.kt:450`; the right consumer is
`PO/Infantry_ChatFeed/src/FeedRenderers.kt:80-83`.

**Copies and risks.**
- `SelectionContainer` x6: `PO/Infantry_ChatFeed/src/ChatFeedSurface.kt:399` (the whole transcript `LazyColumn`: the shape
  that crashed on icon clicks, 6916e986), `uiCore/ChatTableView.kt:61` (nested inside it), `uiCore/ReasoningPanelUi.kt:123`,
  `uiCore/ConfigDialog.kt:874`, `uiCore/AboutSection.kt:51`, `SSL/infantryUi/.../DaemonRefusalCard.kt:39`.
  `DisableSelection` only in `uiCore/plugin/ChildSlot.kt:65`, `:91`: message actions and chips inside the transcript are
  not excluded.
- `PO/Infantry_Board/src/TicketCommentsPanel.kt:90`, `:109` hard-code `BuiltInChatMarkdownEngine` and
  `DefaultChatBlockRenderer`, so ticket comments ignore the user's engine and renderer overrides.
- `PO/Infantry_ChatFeed/src/MessageActions.kt:151` `plainMessageText`: a second, regex markdown stripper.
- `PO/Infantry_Chat/src/ChatPanel.kt` (453 lines) and `ChatSurface.kt` (1,024 lines) share almost the same import block,
  likely a split leftover; unused tooltip and `buildAnnotatedString` imports in `ChatPanel.kt`, `ChatFeedSurface.kt`,
  `AssistantPopup.kt`, `App.kt`.

**Seam to have.** One `SelectableText` / `SelectableMessage` scope per message (never around a lazy list), interactive
children inside `DisableSelection` by construction; every markdown render through `LocalChatMarkdown`; a
`ChatMarkdown.plainText(blocks)` that replaces the regex stripper. Size S-M.

## Progress and spinners (CORE-PROGRESS-DOWNLOAD-1)

8 `CircularProgressIndicator` / `LinearProgressIndicator` calls (`PO/Infantry_Settings/src/PluginsScreen.kt` 3 plus its
own `ProgressStrip` `:260`; `SSL/infantryUi/.../UpdatePrompt.kt` 2; `ConnectScreen.kt` 1; `PO/Infantry_ChatFeed/src/PendingSendRow.kt` 1;
`uiCore/ServerUpdateSection.kt` 1 plus its own `Progress` `:81`) and five hand-made animations (`uiCore/ProgressMeter.kt:65`,
`uiCore/RunPills.kt:83`, `ChatFeedSurface.kt:573`, `BackgroundWorkRow.kt:93`, `uiCore/Splash.kt:119`). Covered by
CORE-PROGRESS-DOWNLOAD-1 (1775ad12, todo); no separate ticket.

## 3b. Error handling on the daemon side

**Seams.** Daemon halves: `PluginGuard` (`SSL/infantry/~/daemon/plugins/PluginGuard.kt:24`, used through `Loaded.call`,
`DaemonPluginHost.kt:111`): timeouts, CPU budget, disable-on-throw, circuit breaker. Harness halves: a second, separate
system, `PluginFailure`/`onFailure` in `SSL/infantryCore/~/harness/ApiHarnessBridge.kt` (137-259),
`ApiSessionAdapter.kt` (62-171) and `HarnessRegistry.kt` (106-201), whose default sink is `System.err`
(`ApiHarnessBridge.kt:49`, `HarnessRegistry.kt:62`). Two containment systems for one concern.

**Swallowing.** Main sources: 1,331 `runCatching`, 541 `.getOrNull()`, 276 bare `runCatching { ... }` statements whose
result is dropped, 52 `catch (x: Throwable)`, 56 `catch (_: X)`, 3 empty `catch {}`, 0 `printStackTrace()`.

| Where | Count |
|---|---|
| Bare `runCatching` statements: `DaemonMain.kt` 24, `SessionManager.kt` 18, `BgCli.kt` 15, `TicketBoard.kt` 12, `PO/Infantry_Processes/.../ProcessWatch.kt` 7 | 276 total |
| `.getOrNull()`: `SSL/infantryCore/~/settings/SettingsService.kt` 41, `DaemonMain.kt` 25, `SSL/infantryUiCore/.../model/Model.kt` 15, `DaemonPluginHost.kt` 14, `ProcessWatch.kt` 13, `CliMain.kt` 12, `TicketBoard.kt` 12 | 541 total |
| `Throwable` caught and dropped: `SSL/infantryBoard/~/session/InfantryEnv.kt:66`, `SSL/infantryCore/~/usage/UsageRefresher.kt:64`, `SSL/infantryUiCore/.../net/DaemonClient.kt:107`, `PO/Infantry_Claude/harness/.../ClaudeTtySession.kt:261` | 4 |

Most `runCatching` is legitimate (parse, optional file). The defect is the dropped result: a failure that leaves no
log line. This is how the worker-watch hook kept failing silently (owner, 2026-11-07: hook failures must be loud).

**Seam to have.** One fault path: harness failures go through the same reporter as `PluginGuard` (one fault record, one
log line, one notification through NOTIFY-CORE-1). Plus one helper, `logFailure(where)` on a `Result`, so "ignore but
log" is one call. Size L (mechanical, many files); the guard only stops new bare `runCatching` statements.

## 4. Settings reading and writing

**Seams.**
- `DaemonApi.settings: PluginSettings` (`SSL/infantryDaemonApi/~/daemon/api/processes/ProcessesApi.kt:188`), backed by
  `SettingsService.forPlugins()` (`SSL/infantryCore/~/settings/SettingsService.kt:789`).
- `DaemonApi.config(key)` (`DaemonApi.kt:41`): the plugin's own `module.json` `daemon.config`. Used by Usage, Scripting,
  Updater, Pace, Notify and JobFeed.
- `HarnessHost.setting` (`SSL/infantryHarnessApi/.../HarnessHost.kt:31`): only Claude and the example harness use it.
- UI: `plugin.api.PluginSettings` (`SSL/infantryPlugins/src/commonMain/.../plugin/api/Services.kt:58`, get/set,
  file-backed by `FilePluginSettings`, `plugin/host/PluginFolders.kt:213`).
- Core only: `SettingScopes` (`SSL/infantryCore/~/settings/ScopedSettings.kt:49`), not reachable by plugins.

**Problems.**
- Two unrelated interfaces are both named `PluginSettings`. The daemon one lives in the `processes` package.
- The daemon one is read-only and gated by a prefix allow-list, `PLUGIN_READABLE` (`SettingsService.kt:1021`). Its KDoc
  still says `limits.*`, `watch.*`, `processes.*` only, but `images.`, `questions.` and `prompts.timeoutSec` were added
  one feature at a time (the 17e14e91 finding, still growing).
- There is no per-plugin key namespace: a plugin cannot own `<pluginId>.*` keys with scopes.

**Copies (plugins reading settings their own way).**

| Where | How |
|---|---|
| `PO/Infantry_Updater/daemon/.../UpdateConfig.kt:96`, `:124-135`; `UpdaterDaemonPlugin.kt:81`; `UpdaterEnv.kt:15` | its own `update.properties` plus a `settings.properties` mirror; sysprop/env |
| `PO/Infantry_Updater/daemon/.../UpdateManifest.kt:35` | a properties resource |
| `PO/Infantry_Claude/harness/.../ClaudeCommand.kt:60`, `:76`, `:78`, `:80`; `ClaudeStatsUsage.kt:130` | `System.getenv`/`getProperty`, bypassing `host.setting` |
| `PO/Infantry_OpenCode/harness/.../OpenCodeCommand.kt:29`, `:34`; `OpenCodePermissions.kt:131`; `OpenCodeConfigReader.kt:36`; `OpenCodeServeSession.kt:66` | `System.getenv` |
| `PO/Infantry_Pi/harness/.../PiCommand.kt:45` | `INFANTRY_NODE_EXE` env |
| `PO/Infantry_Processes/daemon/.../DaemonDirReader.kt:18` | `System::getenv` |
| `PO/Infantry_Roles/daemon/.../AvatarOverrides.kt:82`, `RoleCatalogProbe.kt:26` | `INFANTRY_UI_DATA_DIR` env |
| `plugins/demo/Demo_Lock/.../DemoTurnLimit.kt:90` | `System::getenv` |
| `SSL/infantry/~/daemon/RemoteAccess.kt:27-53` (core) | its own `network.properties` |

**Seam to have.** One scoped plugin settings API on both sides: `settings.get/set(key)` where the plugin's own
`<pluginId>.*` and declared `module.json` `config` keys are readable and writable, and core keys are readable when the
`module.json` declares them (`settings.reads: [...]`), instead of a hand-grown prefix list. `HarnessHost.setting` is the
harness door to the same store. Size M (about 12 files).

## 5. HTTP calls and retry/backoff

**Seams.** Daemon side: none for general HTTP. `infantryDaemonApi` has no fetch API (`Updates.fetch`,
`daemon/api/updates/Updates.kt:47`, is an update check). Lua: `http.fetch` (`PO/Infantry_Scripting/daemon/.../FetchBridge.kt:40`,
its own client at `:46`, a capability, off by default); Kotlin plugins cannot reach it. UI: `createPlatformHttpClient()`
(`SSL/infantryUiCore/src/commonMain/.../ui/net/PlatformHttpClient.kt:8`, Ktor, with jvm/android/wasm actuals), used by
`HttpServerUpdateApi:30`, `HttpPluginsApi:25`, `DaemonClient:44`, `DaemonLink:37`.

**Copies: 18 sites, about 21 clients built.**

| Where | What |
|---|---|
| `SSL/infantryCore/~/usage/Limits.kt:103` | provider usage limits, own Retry-After parsing |
| `PO/Infantry_Claude/harness/.../ClaudeStatsUsage.kt:185` | `newHttpClient()` per call; the same Claude usage endpoint as `Limits.kt` |
| `SSL/infantry/~/daemon/HealthGate.kt:135`, `StallProbe.kt:59` | loopback probes |
| `SSL/infantry/~/daemon/LoopbackFrames.kt:32` and `SSL/infantry/~/daemon/plugins/LoopbackFrames.kt:31` | two near-identical files (74 and 70 lines) |
| `SSL/infantry/~/daemon/plugins/manager/PluginManager.kt:153` | plugin catalog and jar download |
| `SSL/infantry/~/cli/CliMain.kt:6575`, `:6611`; `SSL/infantry/~/client/ClientMain.kt:82` | CLI WebSocket clients |
| `PO/Infantry_Updater/daemon/.../GitHubReleases.kt:95` | GitHub releases API, manual redirects |
| `PO/Infantry_Updater/daemon/.../ServerUpdateCli.kt:38`, `:123` | two clients, one built per health call |
| `PO/Infantry_Updater/androidSrc/AndroidSelfUpdate.kt:171` | `HttpURLConnection`, manual redirect loop (`:102`) |
| `PO/Infantry_RemoteRunner/daemon/.../LinkClient.kt:36` | runner-link WebSocket |
| `PO/Infantry_OpenCode/harness/.../OpenCodeServeSession.kt:65` | opencode serve REST and SSE |
| `SSL/infantryUiCore/src/jvmMain/.../ui/plugin/JvmEndpointAccess.kt:64` | UI plugin endpoint calls |
| `SSL/infantryUiCore/src/jvmMain/.../ui/voice/plugin/VoicePluginStore.kt:23` | voice plugin download |
| `SSL/infantryUi/~/ui/WindowActivation.kt:66` | `HttpURLConnection` to the local port |
| `plugins/examples/Acme_NewsAgent/.../NewsAgentDaemon.kt:98` | example plugin, RSS fetch: teaches the wrong pattern |

**Retry/backoff: 10 independent implementations, no shared helper.** `GitHubReleases.kt:173` `sendWithRetry`
(UPDATER-NET-MSG-1, settings `UpdateConfig.kt:32-49`); `SSL/infantryCore/~/session/SessionStore.kt:242-267` and
`SSL/infantryCore/~/mailbox/MailStore.kt:150-175` (the same replace-retry loop, copy-pasted); `UsageRefresher.kt:82`;
`Limits.kt:353-449`; `CliMain.kt:175`, `:6487` (reconnect table); `SSL/infantryUiCore/.../ui/net/ReconnectPolicy.kt:14`
(UI reconnect); `DaemonPluginHost.kt:936-955` (auto-reload); `plugins/demo/Demo_Lock/.../DemoLock.kt:97`.

**Seam to have.** Daemon: `DaemonApi.http` (one shared client: timeouts, proxy, redirects, size cap, secrets as headers
never logged) with a `retry(policy)` helper whose counts and delays are named settings. The Lua `http.fetch` becomes a
front door to it. File downloads go to CORE-PROGRESS-DOWNLOAD-1's downloader (1775ad12), which sits on the same client.
UI: `createPlatformHttpClient()` stays the UI seam; `JvmEndpointAccess` and `VoicePluginStore` move onto it. A `Backoff`
value type in `infantryDaemonApi` (no I/O) serves `ReconnectPolicy`, `CliMain` and the stores. WebSocket clients
(CLI, client, runner link, loopback) are a second, smaller seam: one `LoopbackFrames`. Size L (18 sites, 4 modules).

## 6. Process spawning and background jobs

**Seams.** `ProcessRunner` (`SSL/infantryRunner/~/runner/ProcessRunner.kt:65`; `PipedProcessRunner:24`,
`TerminalRunner:50`) for agent runs; `HarnessHost.start` (`HarnessHost.kt:42`) for harness helpers;
`BoundedCommand.runBounded` (`SSL/infantryCore/~/harness/BoundedCommand.kt:40`); `GitCli`
(`SSL/infantryCore/~/worktree/GitCli.kt:35`). The DaemonApi `Processes` interface (`ProcessesApi.kt:93`) only observes and
kills: **a daemon plugin has no seam to start a process**, so each one calls `ProcessBuilder`.

**Copies: 34 `ProcessBuilder` sites in 25 files.**

| Where | What |
|---|---|
| `PO/Infantry_Pi/harness/.../PiModels.kt:148-155` | `runBounded`, a verbatim copy of `BoundedCommand` |
| `PO/Infantry_Processes/daemon/.../ProcessWatch.kt:508` | graceful-stop command |
| `PO/Infantry_Scripting/daemon/.../ExecTools.kt:208` | script exec with its own tree kill (`:219`) |
| `PO/Infantry_Board/daemon/.../BoardTool.kt:115` | `git rev-parse`, bypasses `GitCli` |
| `PO/Infantry_Updater/daemon/.../UpdateConfig.kt:239`; `MsiHandoff.kt:62` | `git credential fill`; `cmd.exe /c start` |
| `SSL/infantryCore/~/mailbox/FinishNoticeMonitor.kt:307`, `:317`; `session/AutoProgress.kt:77`; `SSL/infantry/~/cli/BoardSummary.kt:50`; `testrun/ProcessSuite.kt:44` | raw git, bypassing `GitCli` |
| `SSL/infantry/~/cli/BgCli.kt:355`, `:476`, `:717`, `:892`; `ProgressCli.kt:121`; `testrun/ProcessSuite.kt:17` | background jobs and the test runner |
| `SSL/infantry/~/daemon/DaemonPort.kt:130`; `SSL/infantryCore/~/session/ProcessHost.kt:73`; `keystore/OsVaults.kt:22`, `:69`; `harness/install/HarnessInstaller.kt:36` | core one-offs |
| `SSL/infantryUiCore/.../PluginSpeaker.kt:134`, `JvmSpeaker.kt:51`, `DesktopPathPicker.kt:92`, `FreshShell.kt:61`; `SSL/infantryUi/~/ui/InstalledCli.kt:112` | UI-side spawns |

"Kill descendants, then `destroyForcibly`" is written 9 times (`BgCli.kt:1024`, `DaemonMain.kt:1484`, `KeptProcess.kt:66`,
`ProcessSuite.kt:13`, `PluginSpeaker.kt:176`, ...); `taskkill` only in `PipedProcessRunner.kt:113`. No code sets a
no-console-window flag; the only hidden-window handling is `-WindowStyle Hidden` in `OsVaults.kt:37` (open issue I2, the
console pop-up, is this missing seam).

**Seam to have.** `DaemonApi.processes.run(spec)` / `start(spec)` (bounded output, timeout, low priority, hidden window
on Windows, tree kill, registered with the watch so the Processes tab sees it), built on `BoundedCommand` and the bg job
runner; `GitCli` exposed to daemon plugins for git. Harness halves keep `HarnessHost.start`. Size L (25 files).

## 7. File/path handling and the state dir

**Seams.** `stateDir()` (`SSL/infantryCore/~/session/SessionStore.kt:70`, with `infantrySetting`, `InfantrySetting.kt:16`,
and `StateDirMigration`); `InfantryEnv` (`SSL/infantryBoard/~/session/InfantryEnv.kt:14`; an env helper that lives in the
board module); `DaemonApi.stateDir` (`DaemonApi.kt:36`) and `HarnessHost.stateDir` (`:24`) for plugin data; `ProjectFiles`
(`SSL/infantryDaemonApi/~/daemon/api/files/ProjectFiles.kt:19`) for `<project>/.infantry`.

**State dir recomputed: 8 places.** `PO/Infantry_Updater/daemon/.../UpdaterEnv.kt:22` (a copy of `stateDir()` without
the legacy `.agenthq` handling) and `UpdateConfig.kt:127` (builds `<state>/plugin-data/Infantry_Updater` by hand);
`SSL/infantryUiCore/src/jvmMain/.../JvmPlatformHost.kt:244-246` (own resolution, falls back to a relative
`File(".infantry")` at `:190`, `:197`, `:222`, `:231`); `SSL/infantryUi/~/ui/InstallProfile.kt:93-121`, `ShellApp.kt:29`;
`SSL/infantry/~/cli/CliMain.kt:4638`; `SSL/infantry/~/daemon/DaemonMain.kt:4403`;
`PO/Infantry_Roles/daemon/.../AvatarOverrides.kt:82-83`, `RoleCatalogProbe.kt:26`;
`PO/Infantry_Processes/daemon/.../DaemonDirReader.kt:18-19`.

**Atomic writes: no helper; 22 hand-rolled tmp-then-move sites, 4 strategies** (`ATOMIC_MOVE` with fallback,
`REPLACE_EXISTING` only, `renameTo` with delete-and-retry, `createTempFile`). Core: `SessionStore:248`, `MailStore:156`,
`ProgressEstimates:71`, `AgentOverrides:52`, `Keystore:277` (not atomic), `LockAttribution:281`, `StateDirMigration:35`,
`JsonStoryStore:104`, `RemoteStarts:84`, `SessionHistoryStore:180`, `OwnerCredential:56`, `PluginManager:279`, `:320`,
`ProjectFiles:98`, `IhqInstaller:177`. Plugins: `PaceStore:73`, `NotifyService:167`, `RoleService:81`, `LeadNoteStore:213`,
`LinkTls:51`, `ProcessLedger:233`, `CommandStore:142`, `ServerInstall:69`, `:125`, `ClaudeCommand:123`. On Windows a
file lock (antivirus, indexer) breaks `ATOMIC_MOVE`; only two of the 22 retry.

**Seam to have.** `AtomicFiles.write(path, bytes)` / `writeText` in `infantryDaemonApi` (no daemon state, so plugins and
core share it; Windows-lock-aware retry from `SessionStore`), and one `stateDir` resolver every JVM process uses (the UI's
`JvmPlatformHost` and the CLI included). Size M-L (30 sites, mechanical).

## 10. Logging

**Seams.** `DaemonApi.log` (`DaemonPluginHost.kt:1085`, `[plugin id]` prefix); `HarnessHost.log`
(`ModuleHarnessHost.kt:64`); `DaemonLog` (`SSL/infantry/~/daemon/DaemonLog.kt:17`) redirects System.out/err into the
rolling log. No SLF4J, KotlinLogging or java.util.logging.

**Copies.** 319 `println(` and 168 `System.err` in main sources (infantry 175, of which `DaemonMain` 69 and `ClientMain`
61, some of it real CLI output; infantryCore 53; Infantry_Updater 23; infantryUi 20). Daemon plugins bypassing `api.log`:
`ProcessWatch.kt:117`, `:296`; `ProcessLedger.kt:225`, `:236`; `UpdateService.kt:86`; `OwnerQuestions.kt:208-209`;
`LeadNoteStore.kt:236`; `BoardBuild.kt:71`, `:73`; `ScriptDispatcher.kt:72`, `:77`; `RunDaemonPlugin.kt:24`;
`CommandStore.kt:112`, `:153`; `CommandRegistry.kt:138`. 27 constructors default `log: (String) -> Unit = { System.err.println(it) }`
(`RunnerLinkService:48`, `RemoteStarts:38`, `ForwardedFrames:121`, `PolicyProvider:28`, `UsageRefresher:21`, `Limits:317`,
`StallProbe:28`, `AgentModules:20`, `HarnessModules:48`, `TicketEventLog:31`, `AgentRegistry:31`, `StateDirMigration:26`,
`ProcessHost:154`, `RunnerWatch:42`, ...): a test that forgets the argument writes to stderr, and nothing carries a level.

**Seam to have.** `DaemonApi.log` with levels (`info/warn/error`, plus a `Throwable`), the same interface handed to core
classes instead of `(String) -> Unit` defaults; console output in CLI commands stays `println` (allowed by the guard
in `cli/`). Size M.

## 11. JSON parsing helpers

**Seams.** None used broadly: `PluginJson` (`SSL/infantryPlugins/.../ModuleResolver.kt:15`), `ManagerJson`
(`PluginCatalog.kt:117`), `wireJson` (`WireProtocol.kt:2574`), `CapabilityWire.json` (`CapabilityWire.kt:26`). A public
`JsonObject.str` exists in `TodoAdapter.kt:55` and goes unused elsewhere.

**Copies.** 215 local `Json { ... }` instances, about 73 of them the identical `Json { ignoreUnknownKeys = true }`
(infantryCore 39, infantry 39, infantryUiCore 22, Scripting 19, infantryBoard 11, Agents 10, Board 9). About 40
definitions of `str`/`string`/`long`-style `JsonObject` helpers in about 30 files (`StreamUsage.kt:174-176`,
`Limits.kt:275-276`, `SessionStatsService.kt:193-197`, `RequestUsage.kt:55-56`, `HarnessDescriptor.kt:200-202`,
`DescriptorOutput.kt:139-151`, `EventNormalizer.kt:368`, `OpenCodeTranslator.kt:112`, `OpenCodeStats.kt:79-80`,
`ChatDaemonPlugin.kt:187`, `ClaudeTranscript.kt:190-192`, `ClaudeStatsUsage.kt:119-121`, `ProcessesDaemonPlugin.kt:111`,
`AttentionDaemonPlugin.kt:133`, `PluginsDaemonPlugin.kt:84`, `SettingsDaemonPlugin.kt:40`, `RolesDaemonPlugin.kt:53`,
`PaceChartModel.kt:104-109`, `AndroidSelfUpdate.kt:150`, `DemoLock.kt:247`, ...). Tool-argument helpers re-declared in 8
files (`ToolArgs.kt:15-18`, `SpawnTools.kt:78-80`, `SessionReadTools.kt:273`, `WireTickets.kt:61`, `RunCommandTools.kt:135`,
`SaveTools.kt:45`, `ScriptHooks.kt:465`, `ProjectIsolation.kt:108`).

**Whole-file copies.** `SSL/infantryCore/~/harness/claude/ClaudeStreamTranslator.kt` and
`PO/Infantry_Claude/harness/.../ClaudeStreamTranslator.kt` (168 lines each); `SSL/infantryCore/~/harness/EventStreamTranslator.kt`
(191) and `PO/Infantry_Pi/harness/.../EventStreamTranslator.kt` (193). The core copies are also concrete harness knowledge in
core (see Structure).

**Seam to have.** A `JsonKit` in a leaf every module already sees (`infantryPlugins` commonMain, so the wasm client gets
it too): `Json.lenient` (the `ignoreUnknownKeys` instance) and the `JsonObject.str/long/bool/obj/arr` readers; tool-arg
readers on `DaemonTool` args in `infantryDaemonApi`. Size M (mechanical, about 40 files).

## 12. Caching

**Seam.** None: no shared TTL cache or memo type.

**Copies: 11 ad-hoc TTL caches.** `SSL/infantryCore/~/harness/descriptor/DescriptorModels.kt:110-120` (30 s) and its
verbatim copy `PO/Infantry_Pi/harness/.../PiModels.kt:30-40`; `PO/Infantry_OpenCode/harness/.../OpenCodeHarnessPlugin.kt:121-130`
(60 s) and `OpenCodeConfigReader.kt:97`; `SSL/infantryCore/~/usage/Limits.kt:355-379` and `:437-449` (two in one file);
`SSL/infantryCore/~/session/AutoProgressHistory.kt:16-24` (60 s); `SSL/infantryCore/~/stats/SessionStatsService.kt:54-55`;
`PO/Infantry_Pace/daemon/.../UsageFit.kt:328-346` (bucketed by `now / TTL_MS`); `PluginManager.kt:205`, `:300-302` (on-disk
catalog); `SSL/infantryCore/~/db/LockAttribution.kt:132`, `:262`. Unbounded caches with no TTL or size cap:
`NotifySounds.kt:56`, `BgJobNotifier.kt:118`, UI `AhqHarnessIcons.kt:150`, `AgentAvatar.kt:115`, `TabFactory.kt:537`,
`ResolvedConfig.kt:33`, `Controller.kt:1482`. Most TTLs are literals, not named settings.

**Seam to have.** `TtlCache<K, V>(ttl, maxEntries, clock)` in `infantryPlugins` commonMain (pure, no I/O, testable with
a fake clock), the TTL taken from a named setting. Size S-M (11 sites).

## 13. Hashing (found on the way)

No helper: 24 `MessageDigest.getInstance("SHA-256")` sites in main sources, among them `fun sha256` declared in
`SSL/infantry/~/update/ReleaseAssets.kt:35`, `SSL/infantry/~/update/UpdateEngine.kt:157`,
`SSL/infantryDaemonApi/~/daemon/api/board/TicketImages.kt:65`, `SSL/infantryUiCore/src/commonMain/.../config/ConfigManifests.kt:54`
(`sha256Hex`), `SSL/infantryUiCore/src/jvmMain/.../voice/plugin/VoicePluginStore.kt:187`,
`PO/Infantry_Scripting/daemon/.../GlobalScripts.kt:77`, `PO/Infantry_Updater/androidSrc/AndroidSelfUpdate.kt:163`,
`PO/Infantry_Updater/daemon/.../ReleaseAssets.kt:20`, and inline digests in `InfantryMcp.kt:104`, `OwnerCredential.kt:99`,
`PluginManager.kt:858`, `PairCodes.kt:73`, `RemoteStarts.kt:252`, `RunnerLinkStore.kt:143`, `WebhookIngress.kt:66`,
`AccessPolicy.kt:456`, `Keystore.kt:308`, `OfficialTrust.kt:35`, `KeystoreSavedServers.kt:57`, `ImageUploads.kt:152`,
`ClaudeCommand.kt:119`, `LinkTls.kt:64`, `Updater.kt:105`. `PluginManager.kt:19` imports core's `update.ReleaseAssets` only
for its `sha256`. Seam to have: `Hashes.sha256Hex(bytes|text|file)` next to `AtomicFiles` (folded into the file ticket).

## Structure problems

### Files over ~2,000 lines

| File | Lines | Ticket |
|---|---|---|
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/cli/CliMain.kt` | 6,678 | AI-FIX-1 d6904191 (backlog) |
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/DaemonMain.kt` | 4,679 | AI-FIX-3 1c00d684 (backlog) |
| `superiorSoftLibrary/infantryBoard/src/main/kotlin/com/superiorsoftllc/infantry/backlog/TicketBoard.kt` | 2,658 | AI-FIX-9 51e58aa0 (backlog) |
| `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/protocol/WireProtocol.kt` | 2,657 | new: wire-contract ticket below |
| `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/session/SessionManager.kt` | 2,477 | AI-FIX-9 51e58aa0 (backlog) |
| `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/model/Model.kt` | 2,450 | AI-FIX-2 b86fdcc8 (backlog) |
| `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/net/Controller.kt` | 2,214 | none yet: add to the size-guard ticket below |

Command: `find superiorSoftLibrary plugins -name "*.kt" -not -path "*/build/*" -not -ipath "*test*" -print0 | xargs -0 wc -l | sort -rn`.
`DaemonMain.kt` grew from 4,306 (AI-FIX-3's count, 2026-11-05) to 4,679 in two days: the split tickets alone do not stop
growth. No guard caps file size.

### The wire contract exists twice

`superiorSoftLibrary/infantryProtocol/src/main/kotlin/com/superiorsoftllc/infantry/protocol/Protocol.kt:131` (JVM, 277
`const val`s) and `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/protocol/WireProtocol.kt:20`
("client-owned common DTOs", 266 `const val`s) both declare `Envelope` and the frame names. `infantryProtocol` is
JVM-only, so the wasm/Android client keeps its own copy. No parity test compares the two sets; a frame added on one
side only is found at run time. Fix: make `infantryProtocol` Kotlin Multiplatform (commonMain) and delete the copy, or,
as the cheap step first, a parity test over the frame constants.

### Features in core

| Where | What | Belongs in |
|---|---|---|
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/tokens/OpenCodeUsageParser.kt:18` | A parser of one harness's (opencode) output, in core. Its only main caller is `plugins/official/Infantry_OpenCode/harness/.../OpenCodeTranslator.kt`. `HarnessCoreGuardTest` misses it (it checks harness classes, not harness knowledge). | `Infantry_OpenCode/harness` |
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/testrun/` (5 files, 464 lines), wired at `daemon/plugins/DaemonPluginHost.kt:431` | The full-suite test runner and failure-to-ticket ledger (FULLSUITE-2): an orchestration feature. | a plugin's daemon half (orchestration layer) |
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/triggers/` (7 files, 1,413 lines) | Triggers. | Infantry_Triggers: TRIG-2c b5ca125e (backlog) |
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/update/ReleaseAssets.kt` | GitHub release asset writer, duplicated by `plugins/official/Infantry_Updater/daemon/.../updater/ReleaseAssets.kt`; core's copy is still imported by `daemon/plugins/manager/PluginManager.kt:19` only for its `sha256`. | delete the core copy; hashing seam below |
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/notify/Notify.kt` | VOICE-1 notify levels and handler in core. | NOTIFY-CORE-1 2859638f (in progress) |
| `superiorSoftLibrary/infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/harness/claude/ClaudeStreamTranslator.kt` (168 lines) and `.../harness/EventStreamTranslator.kt` (191) | Concrete harness stream translators in core, near-identical to `PO/Infantry_Claude/harness/.../ClaudeStreamTranslator.kt` (168) and `PO/Infantry_Pi/harness/.../EventStreamTranslator.kt` (193). The core copies serve the JSON descriptor path (`harness/descriptor/DescriptorOutput.kt`). | one generic translator in core driven by the descriptor; the plugins keep theirs |
| `superiorSoftLibrary/infantryBoard/src/main/kotlin/com/superiorsoftllc/infantry/session/InfantryEnv.kt` | The legacy-env helper every module needs lives in the board module. | a leaf (`infantryDaemonApi` or `infantryProtocol`) |
| `superiorSoftLibrary/infantryDaemonApi/src/main/kotlin/com/superiorsoftllc/infantry/daemon/api/processes/ProcessesApi.kt:188` | The daemon `PluginSettings` sits in the `processes` package and shares its name with `plugin.api.PluginSettings` (UI). | section 4 |

### Dead code

| Where | Evidence |
|---|---|
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/tokens/` `JsonFileUsageLedger.kt`, `UsageLedger.kt`, `UsageTypes.kt` | No main-code caller; only their own tests use them (an old measurement experiment, `MULTI_AGENT_ROOM`). |
| `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/experiments/pitoolbridge/` | An experiment package in core main code, still registered from `DaemonMain.kt`. Keep or move out on purpose. |

No tool finds unused declarations today; the dead-code ticket adds one check.

## The consolidation tickets (backlog, filed 2026-11-07)

Each ticket names a target seam, the files to migrate, grep-able acceptance checks and one relapse guard test. Most
guards are ratchets, like `HandCursorGuardTest`: they record today's count of the ad-hoc pattern as a ceiling that
may only go down, so a migration can land in parts and a new copy fails the build at once. Weight is by risk
(1 = trivial, 5 = cross-cutting, changes the SDK).

| Ticket | Weight | Guard it adds |
|---|---|---|
| NOTIFY-MIGRATE-1 7664e501 (after NOTIFY-CORE-1 2859638f) | 3 | NoAdHocNoticeGuardTest |
| TOOLTIP-SEAM-1 d6232f36 | 3 | TooltipSeamGuardTest |
| ERROR-UI-SEAM-1 e5ea1600 | 3 | ErrorUiGuardTest |
| FAULT-LOG-1 a279d927 | 4 | SilentFailureGuardTest |
| PLUGIN-SETTINGS-2 2c50624d | 4 | PluginSettingsGuardTest |
| HTTP-SEAM-1 d2de18a0 | 4 | HttpSeamGuardTest |
| PROC-SEAM-1 338cc5b5 | 4 | ProcessSeamGuardTest |
| FILES-SEAM-1 8e5fefc7 (also hashing) | 3 | FilesSeamGuardTest |
| CLICK-GUARD-2 9737a388 | 2 | widens `HandCursorGuardTest` |
| CHAT-SELECT-1 caaf1e9e | 3 | ChatRenderGuardTest |
| LOG-SEAM-1 b5688776 | 3 | LoggingGuardTest |
| JSON-KIT-1 38ec058e | 2 | JsonKitGuardTest |
| CACHE-SEAM-1 76613922 | 2 | CacheSeamGuardTest |
| WIRE-PARITY-1 0ddbd10e | 4 | WireParityTest |
| CORE-LAYER-1 96b440ae | 4 | widens `HarnessCoreGuardTest`, adds UnusedMainTypesTest |
| FILE-SIZE-GUARD-1 98b605ee | 2 | FileSizeGuardTest |

Progress and downloads are not a separate ticket: CORE-PROGRESS-DOWNLOAD-1 1775ad12 already covers them.

Order advice:

- CLICK-GUARD-2 and FILE-SIZE-GUARD-1 first: small, and they stop the growth while the larger tickets run.
- HTTP-SEAM-1 and CORE-PROGRESS-DOWNLOAD-1 share one client; land the HTTP seam first or in the same series.
- PROC-SEAM-1 and FILES-SEAM-1 both add to `DaemonApi`; share one API version bump.
- NOTIFY-MIGRATE-1 waits for NOTIFY-CORE-1; ERROR-UI-SEAM-1 can then reuse its notice surface for error cards.

## Decisions that are expensive to reverse

- **One wire contract.** WIRE-PARITY-1 either makes `infantryProtocol` multiplatform (one source for the daemon and
  the clients) or keeps two copies under a parity test. The first is the cleaner end state; switching later means
  touching every client DTO twice.
- **SDK additions are promises.** A daemon HTTP client, a process run call, an atomic write helper, a levelled log and
  scoped plugin settings in the SDK are used by out-of-tree plugins once published. Shape them once, small, and
  version them; removing one later breaks plugins we cannot see.
- **The settings namespace.** PLUGIN-SETTINGS-2 fixes the rule "a plugin reads `<pluginId>.*` plus what its
  `module.json` declares it reads". Narrowing that later breaks plugins; widening it is cheap.
- The other tickets are internal (UI core, daemon core, guards) and cheap to change later.
