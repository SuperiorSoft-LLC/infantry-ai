# Notifications

NOTIFY-CORE-1 (2026-11-07). Infantry AI has ONE notification system. Every feature and plugin tells the user something
through it, and a plugin can take over how notifications are shown. This page is the design, the settings and the
inventory of the ways the app told the user something before (with what moved and what is still to move).

## 1. The bus

One API, the same for every caller:

| Caller | How it posts | Where it lands |
|---|---|---|
| A UI plugin | `registrar.services.get(Services.NOTIFICATIONS)?.notify(Notification(...))` (or `LocalShellContext.current?.toast?.notifications`) | the window's bus |
| A daemon plugin | `api.notifications.notify(Notification(...))` (DaemonApi 1.53.0) | the daemon hub, pushed to every client's bus |
| A script or agent | `ih.call("notify", {title = ..., level = ...})`, tool `infantry_notification_post` | the daemon hub, pushed to every client's bus |
| The agents' `infantry_notify` tool | Infantry_Notify's `notify_push`, which its UI half now posts on the bus (OS channel) | the window's bus |

A `Notification` (`superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/Notification.kt`) has:
`id`, `source` (stamped by the host: the module name, `daemon`, `script`), `level` (`NotificationLevel`: info, success,
warning, error, urgent), `title`, `body`, `actions` (`NotificationAction(id, label)`), `sticky` / `timeoutMs`,
`dedupeKey`, `channels` (empty = the level's route) and optional `ticketId` / `sessionId` / `projectId` links.

The bus of one host is `NotificationCenter`
(`superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/host/NotificationCenter.kt`):
pure Kotlin, no UI, the same on desktop, web and Android. For each notification it:

1. stamps the id and time;
2. drops a repeat: the same id, or the same `dedupeKey` within `notifications.dedupeWindowSec` (a sliding window, so a
   long outage gives one toast; the repeat count is kept in the history);
3. routes it to its channels: its own `channels`, else `notifications.route.<level>`. During
   `notifications.quietHours` every level but urgent stays off the OS channel;
4. hands it to each channel's ONE active presenter (section 2). A channel nobody can show falls back to in-app, so a
   routed notification is never lost;
5. keeps it in the history (the inbox, newest first, at most `notifications.historyMax`).

The daemon side is `NotificationHub`
(`superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/NotificationHub.kt`): it stamps
and dedupes with the same rule, broadcasts the frame `notification_push`, keeps sticky notifications and replays them to
each client that connects (until a client sends `notification_dismiss`). A pressed action comes back as
`notification_action`; the hub hands it to the posting module's `onAction` listeners and publishes the named event
`notification.action` (a hook can run a script on it). Routing and presenting happen on the clients, where the
presenters are. The frames and their codec are `NotificationWire`; the policy actions are `notifications.act` and
`notifications.dismiss`.

## 2. Channels and presenters

| Channel | Core default | Override |
|---|---|---|
| `inApp` | the shell's toast host (`ShellToast`, the `infantry.shell.toast` block) and the history | a plugin presenter |
| `os` | none | Infantry_Notify (the system tray / Windows toast) registers as its presenter |

A plugin becomes a presenter with `notifications.present(presenterId, channels, presenter)` (a
`NotificationPresenter`: `show(n): Boolean`, `dismiss(id)`); the returned handle unregisters it. Which presenter is
active is a setting, `notifications.presenter.<channel>`:

- blank (default): the last plugin presenter registered for the channel, else the core default;
- `core`: always the core default;
- a module name: that module's presenter, else the core default.

A presenter that throws is contained (PLUGIN-CRASH-CONTAIN-1): the fault is recorded against the module in
`PluginFaults`, the presenter is skipped from then on (until it registers again), and the core default shows the
notification. A presenter that answers `false` (it cannot show this one) falls back to the core default too.

## 3. Settings

All are core keys, validated by `NotificationSettings.validated` (`SettingsService.validated`) and sent to the clients
in the settings frame.

| Key | Default | Meaning |
|---|---|---|
| `notifications.route.info`, `.success`, `.warning`, `.error` | `inApp` | the channels of a level (a comma list of `inApp`, `os`; `none` = nowhere) |
| `notifications.route.urgent` | `inApp,os` | urgent ones reach the OS too |
| `notifications.presenter.inApp`, `notifications.presenter.os` | blank | the active presenter (section 2) |
| `notifications.quietHours` | blank | `HH:MM-HH:MM` local time (may wrap midnight): no OS notifications but urgent |
| `notifications.dedupeWindowSec` | 600 | the dedupe window; 0 = no dedupe; at most 86400 |
| `notifications.historyMax` | 100 | notifications kept in the history; 1 to 10000 |
| `notifications.titleMaxChars`, `notifications.bodyMaxChars` | 200, 4000 | a daemon-side notification's title and body are cut to these lengths before they cross the wire |

## 4. The rule, and its guard

Feature and plugin code never draws its own error toast, snackbar or native dialog: it posts on the bus.
`NotificationGuardTest` (`superiorSoftLibrary/infantry/src/test/kotlin/com/superiorsoftllc/infantry/NotificationGuardTest.kt`)
greps every main source set for JOptionPane, Snackbar, `Toast.makeText`, SystemTray / TrayIcon (outside
Infantry_Notify), `ToastHost(` and `ToastEvent(` (outside the UI core). Its allow-list names each exception with the
reason, and an entry that no longer matches fails the test too, so the list only shrinks.

A question to the user (a confirm, a consent, a form) is a dialog, not a notification, and stays a dialog.

## 5. Inventory (2026-11-07) and migration

Every way the app told the user something when the bus landed:

| Where | What | State |
|---|---|---|
| `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/ToastHost.kt` | the core toast host | now the core default in-app presenter; draws actions, sticky toasts, dismiss |
| `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/ToastService.kt` | shell toast posts (`ToastContext.service`) | kept; `ToastContext.notifications` is the bus |
| `superiorSoftLibrary/infantryUiCore/src/jvmMain/kotlin/com/superiorsoftllc/infantry/ui/plugin/DesktopPlugins.kt` | `Services.NOTIFICATIONS` printed a log line only | migrated: the bus, stamped with the module name |
| `plugins/official/Infantry_Notify/src/NotifyModule.kt` | agents' `infantry_notify` straight to the tray | migrated: posted on the bus (OS channel); Infantry_Notify is the OS presenter |
| `plugins/official/Infantry_Notify/jvmSrc/DesktopNotifier.kt:42` | the tray toast | the OS presenter's drawing (allowed by the guard) |
| `superiorSoftLibrary/infantryUi/src/main/kotlin/com/superiorsoftllc/infantry/ui/UpdatePrompt.kt` | the update check's answers ("Could not reach GitHub", from `plugins/official/Infantry_Updater/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/updater/GitHubReleases.kt:45`; "up to date") as its own card | migrated: bus notifications with "Check again"; the card stays only when no window bus exists |
| same file | download progress, ready-to-install, download failed cards; the update dialogs | progress belongs to CORE-PROGRESS-DOWNLOAD-1 (1775ad12); the dialogs are questions |
| `plugins/official/Infantry_Settings/src/PluginsScreen.kt` | a failed plugin install/update/remove as red text | migrated: also an error notification with Retry; the inline notice stays beside the plugin |
| `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/voice/VoiceUi.kt` | spoken alerts' own toast box | migrated: an in-app notification; the box only without a shell |
| `plugins/official/Infantry_Scripting/daemon/src/main/kotlin/com/superiorsoftllc/infantry/plugins/official/scripting/daemon/ChatNoteTools.kt` | `chat_note` status cards in a session's chat | kept: a timeline item of one session, not a notification |
| `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/model/Model.kt:1645`, `:1989` | the reducer's "ticket started" and prompt toasts | follow-up: post through the bus |
| `superiorSoftLibrary/infantryUi/src/main/kotlin/com/superiorsoftllc/infantry/ui/InfantryWindow.kt:116` | the plugin host-error banner (`PluginFaults.hostError`) | follow-up: a sticky error notification |
| `plugins/official/Infantry_Updater/androidSrc/AndroidSelfUpdate.kt:148` | Android `Toast.makeText` | follow-up (allow-listed) |
| `superiorSoftLibrary/infantryUi/src/main/kotlin/com/superiorsoftllc/infantry/ui/WindowActivation.kt:103` | a JOptionPane "already running" | allow-listed: a second launch whose running instance cannot be reached has no window and no bus, and exits |
| `plugins/official/Infantry_Board/src/AgentTaskActions.kt:128`, `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/shell/blocks/ShellSwitchError.kt:19` | error messages shown as dialogs | follow-up: error notifications |
| Voice Input and Read Aloud (outside plugin repos) | their own error UI ("No microphone is available") | follow-up in their repos: post through `Services.NOTIFICATIONS` |
| the other AlertDialog / `AhqAlertDialog` uses | confirmations and forms | stay dialogs (questions, not notifications) |

## 6. Not yet

- A settings card for the `notifications.*` keys and an inbox view of the history (the data is there:
  `NotificationCenter.history`, `ToastContext.history`).
- Web and Android get the bus through the shell (`ToastContext.notifications`) but no `Services.NOTIFICATIONS`
  provider yet.
