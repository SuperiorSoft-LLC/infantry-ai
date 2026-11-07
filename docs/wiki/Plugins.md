[← Wiki index](index.md)

# Plugins

Infantry AI works like a small operating system: the app is the OS, plugins are the programs. Every tab, panel
section, board card and daemon tool comes from a plugin (also called a module). A plugin is a folder named
`Vendor_Module` holding a `module.json` and, usually, one or two jars.

## Trust tiers

The folder decides the tier. The tier is a **trust label, not a sandbox**: a loaded jar runs inside the app with the
app's access to the machine. Enable only plugins whose authors you trust.

| Folder under `plugins/` | Tier | Meaning |
|---|---|---|
| `core/` | core | Compiled into every build. |
| `official/` | official | Made by the app's own developers. Signed jar, loaded at run time on desktop. |
| `community/` | community | Dropped in by you. Off until enabled, must name `author` and `license`, labelled Community. |
| `examples/`, `_template/` | not loaded | Reference code. A folder starting with `_` is never loaded. |

`module.json` may carry a `"tier"` key; it is ignored (the folder decides). Some shipped manifests still contain
one. That is harmless.

## The two halves

A plugin has up to three parts, each optional, each declared in `module.json`:

- **UI half** (`entry` + `jar`): a Kotlin/Compose class implementing `UiPlugin`. It registers tabs, panel sections,
  blocks, interceptors and observers. Example: `plugins/community/Acme_Hello`.
- **Daemon half** (`daemon`): runs inside the daemon. Adds tools (CLI/MCP), wire frames, routes, settings, storage
  and hooks. Examples: `plugins/examples/Acme_DaemonHello` (smallest), `plugins/official/Infantry_Board`
  (a full one, with tools and frames).
- **Harness half** (`harness`): adds an agent CLI. See [Harnesses and providers](Harnesses.md).

Web and Android clients cannot load a jar at run time, so they get the UI halves compiled in. A community plugin
works on the desktop app and the headless daemon only.

## module.json reference

Fields that exist today. Unknown keys are ignored. Real manifests: `plugins/community/Acme_Hello/module.json`,
`plugins/official/Infantry_Board/module.json`, `plugins/official/Infantry_Scripting/module.json`.

| Field | What it does |
|---|---|
| `name` | `Vendor_Module`; equals the folder name. Required. |
| `version` | Semver. Other plugins' `dependencies` ranges are checked against it. |
| `apiVersion` | Plugin API revision (currently `2`). A different value refuses the module. |
| `author`, `license` | Required for community (license is an SPDX id or `NOASSERTION`). |
| `description` | One line. |
| `defaultEnabled` | `false` = an optional module: off until Settings > Plugins (or `modules.json`) enables it, whatever its tier. Default `true`. |
| `dependencies` | `[{ "id": "Infantry_Git", "version": ">=1.0 <2" }]`. Missing, disabled or out-of-range refuses the module. Ranges: `>= > <= < =`, `1.2`, `^1.2.3`, `~1.2.3`, `*`, `||`. A core module may depend on core modules only. |
| `sequence` | Load order only. |
| `entry`, `jar` | UI class (a `UiPlugin` with a no-arg constructor) and the built jar, relative to the folder. |
| `services` | `notifications`, `storage`, `settings`, `events`, `daemon`, `endpoints`. An unlisted service is `null` in your code. A community plugin also needs each one in `allow` in `modules.json`. |
| `routes` | `["GET /health"]`: the only daemon routes the `daemon` service may call. |
| `events` | Event names you may publish/observe: `tab.opened`, `session.selected`, `modules.loaded`, `ticket.changed`. |
| `tabs` | `[{ "id": "acme.hello", "container": "center" }]`; container is `center` or `right`. |
| `panels` | `[{ "id": "acme.hello.greeting", "panel": "left.board" }]`: a section in a side panel or a named slot (`left`, `right`, `left.board`, or a host's own slot). |
| `preferences` | `[{ "replace": "infantry.git", "with": "acme.git" }]`: replace another plugin's tab or section. Community needs `allowOverride` in `modules.json`; the Acme_Hello example shows a replacement the trust rules refuse. |
| `interceptors` | `[{ "target": "infantry.epics", "name": "hi", "kind": "before\|after\|around" }]`. |
| `observers` | `[{ "event": "tab.opened", "name": "count" }]`. |
| `blocks` | `[{ "slot": "center.top", "name": "banner", "order": 0 }]`; slots `center.top`, `center.bottom`, `right.top`, `right.bottom`. |
| `shell` | `[{ "node": "infantry.shell.footer", "name": "myBar", "mode": "wrap\|replace" }]`: wrap or replace a node of the window's block tree. Some nodes are locked. |
| `slots` | A host declares named slots child plugins can fill (`"slots": ["host.part"]`). |
| `blockTypes` | Block types the plugin defines for block trees (see below). |
| `fills` | Layout nodes the plugin fills, e.g. `"infantry.shell.board"`. |
| `config` | Config cards (see Config fields). |
| `endpoints` | `[{ "id": "echo", "url": "http://localhost:8080/**", "methods": ["GET","POST"], "auth": { "kind": "bearer", "field": "apiKey" } }]`: calls to your own backend by id; the host adds the credential. |
| `platforms` | `["desktop","android","web"]`; absent = all. |
| `actions` | Named actions your controls perform, so a policy preset can switch them off. |
| `daemon` | `entry`, `jar`, `apiAtLeast`, `tools`, `frames`, `hooks`, `config`, `storage`, `env`, `limits`. |
| `harness` | `jar`, `contract`, `ids`, `cli`, `settings`, `hooks`. |

### Daemon half

From `Infantry_Board`:

```json
"daemon": {
  "entry": "...board.daemon.BoardDaemonPlugin",
  "jar": "daemon/build/libs/Infantry_Board-daemon.jar",
  "apiAtLeast": "1.24.0",
  "tools": [
    { "name": "infantry_ticket_get", "action": "board.read", "kind": "read", "description": "Read ONE ticket back." }
  ],
  "frames": [ { "type": "ticket_list", "action": "board.read", "kind": "read" } ]
}
```

Each tool and frame names a policy `action` and a `kind` (`read`, `write`, `destructive`, `spawn`), so policy presets
can refuse it. `hooks` lists lifecycle hooks (`ready`, `incomingMail`). `Acme_DaemonHello` adds `"env"` (environment
variables the plugin may read) and `"limits": { "callTimeoutMs": 2000 }`.

### Block types, panels and slots

The window is a tree of blocks, each with a node id. A plugin that owns a screen declares its block types so
other plugins can add, wrap or replace sections by id. `Infantry_Board` declares 14, e.g.:

```json
{"type": "board.pane", "title": "Board", "takesChildren": true, "fields": [ ... ]},
{"type": "board.filters", "title": "Board filters", "parents": ["board.pane"], "fields": [ ... ]},
{"type": "board.columns", "title": "Board columns", "takesChildren": true, "parents": ["board.pane"], "max": 1}
```

`parents` limits where a type may sit, `max` limits how many, `takesChildren` marks a container. Node ids are API
and are never renamed. The pinned list lives in `CoreShellTreeTest`; the design is in `docs/BLOCKS-LAYOUT-DESIGN.md`.

A host makes part of a screen replaceable by declaring a slot and drawing it with `ChildSlot`; a child plugin fills
it with a `panels` entry whose `panel` is the slot name. Another plugin can add a section before or after (lower or
higher `order`) or replace it with `preferences`.

Infantry_Chat offers four extension points for plugins such as voice input or read-aloud: the slots
`chat.composer.actions` (buttons beside attach) and `chat.message.actions` (actions on each message), `chat.message.header` (small icons in each card's header, left of the time) and the event
`chat.composer.insert` (put text into a draft; sending it needs the Chat setting `chat.allowPluginSend`, off by
default). Their arguments and payload are listed in [UI plugin API](plugin-api.md#the-slots-and-event-infantry_chat-offers).

### Config fields

A `config` card renders in Settings with no UI code from you. From `Acme_Hello`:

```json
{ "node": "acme.hello.greeting", "group": "Hello greeting", "tab": "Plugins", "order": 900,
  "title": "Hello greeting (Acme_Hello)",
  "fields": [
    { "key": "greeting", "type": "radio", "label": "Greeting", "default": "Hello World", "options": ["Hello World", "Hello Nation"] },
    { "key": "message", "type": "text", "label": "Custom message", "default": "" },
    { "key": "shout", "type": "checkbox", "label": "Shout", "default": false },
    { "key": "classes", "type": "classes", "label": "Classes", "slots": { "text": { "tokens": ["big", "bold", "muted"] } } }
  ] }
```

Field types seen in shipped manifests: `text`, `number` (`min`, `max`, `step`), `toggle`, `checkbox`, `radio`,
`multiSelect`, `classes`. A field may carry `scope` (`daemon` for daemon settings), `order`, `default`, `options`.
Your code reads values live with `context.config("<node>")`. Web and Android compile config in, so keep a Kotlin
copy of the declarations and a test that fails when it differs from `module.json` (`AcmeHelloConfigTest`).
More field types: `docs/CONFIG-FIELDS.md`.

## Building

```
./gradlew :plugins:community:Acme_Hello:jar :plugins:community:Acme_Hello:test
```

The build discovers any `plugins/community/*/build.gradle.kts`. The jar holds only your classes: the app provides
the plugin API, Compose and Kotlin. Dependencies you add go into a fat jar or `<module>/lib/*.jar`. Native files go
in `<module>/native/<os>-<arch>/` and load with `System.loadLibrary`.

## Installing

1. Put the folder (with `module.json` and the jar) into `<plugins root>/community/`. The plugins root is
   `INFANTRY_PLUGINS_ROOT`, else `<state dir>/plugins`.
2. Enable it: Settings > Plugins > Modules > Enable, or edit `plugins/modules.json`:

```json
"modules": { "Acme_Hello": { "enabled": true, "allow": ["events", "storage", "daemon", "notifications"] } }
```

3. Restart. A row that did not load says why in one line, and so does the log (`[plugins] Acme_Hello (community): refused - ...`).

`modules.json` also holds `folders` (the folders to scan and their tier) and per-module `allow`, `deny`, and
`allowOverride`.

## Signing

Official plugins are signed jars; the loader accepts them only when the signer matches a pinned Infantry key and
the signed `module.json` equals the one on disk. Packages for installing (`.ihq`) are checked the same way.
A community plugin needs no signature. Status today: the pinned key is a placeholder in the source until the real
signing certificate is set, and dev builds run with `-Dinfantry.plugins.devTrust=true`. See `docs/PLUGIN-MANAGER.md`
and `docs/INFANTRY-INSTALL.md` (section "Official modules and the signing key").

## Hello plugin, step by step

`plugins/community/Acme_Hello` is a block under the Board button whose text you change in Settings.

1. **Copy the template.** `cp -r plugins/_template plugins/community/Acme_Notes`. Set `name`, `jar`, `author`,
   `license` in `module.json`; rename the package and point `entry` at your class.
2. **Declare the block** in `module.json`:
   ```json
   "panels": [ { "id": "acme.hello.greeting", "panel": "left.board" } ]
   ```
   plus the `config` card above. A community plugin owns the config nodes of its own tabs and sections, so the
   card's `node` is the section id.
3. **Write the block** (`HelloGreeting.kt`). It reads its node live, so a Settings change recomposes it:
   ```kotlin
   @Composable
   fun GreetingBlock(context: TabContext) {
       val config = context.config("acme.hello.greeting")
       Text(greetingText(config.string("greeting"), config.string("message"), config.boolean("shout") == true))
   }
   ```
4. **Register it** in `HelloPlugin.register`:
   ```kotlin
   registrar.panel(PanelProvider(id = "acme.hello.greeting", panel = Panels.LEFT_BOARD, order = 10,
       testTag = "acme-hello-greeting-block") { GreetingBlock(it) })
   ```
   Anything registered but not declared, or declared but not registered, is refused and listed in Settings > Plugins.
5. **Build and enable** as above, restart, open Settings > Plugins > "Hello greeting (Acme_Hello)" and change the
   greeting. The block updates live.
6. **Run it from source** (Windows):
   ```powershell
   $env:INFANTRY_STATE_DIR = "$env:TEMP\hello-state"; $env:INFANTRY_PORT = "8812"; $env:INFANTRY_PROVIDER = "stub"
   .\gradlew.bat --no-parallel :superiorSoftLibrary:infantryUi:runRealDaemon
   ```

Deeper guides in the repo: `plugins/GUIDE.md`, `plugins/README.md`, `plugins/_template/README.md`,
`docs/PLUGIN-HELLO.md`, `docs/PLUGIN-ENDPOINTS-DESIGN.md`.

Screenshot: `docs/img/hello-plugin.png` in the repo.

## System notifications (Infantry_Notify)

`plugins/official/Infantry_Notify` (NOTIFY-1) sends system notifications for **milestones and checkpoints only**. Nothing
notifies on its own: a notification is sent only when a script, an agent, the CLI asks, or when a ticket marked as a
milestone enters done. It has a daemon half and a UI half; core only carries the generic frame seam
(`LinkContext.frames` / `LinkContext.sendFrame` in `ShellContext.kt`).

| Where | What it does |
|---|---|
| Desktop (Windows) | a tray toast (`TrayIcon.displayMessage`); clicking it brings the window to the front and opens the ticket. The tray icon appears on the first notification. WAV and mp3 play; ogg does not. |
| Android | a notification on the "Milestones" channel (`urgent` on "Urgent", heads-up); a tap opens the app on the ticket. Android 13+ asks for POST_NOTIFICATIONS once. Shown while the app is connected, or on its next connect. With the opt-in "Background notifications" switch (Notifications card, off by default) a foreground service (`NotifyBackgroundService`) keeps the connection while the app is closed, so a notification arrives within seconds; the daemon queues per device (`notify_ack` and `notify_pending` carry a `device`), so the desktop acking a notification never hides it from the phone. Off the LAN it uses the same daemon URL as the app (Tailscale/WireGuard). |
| Web | the browser Notification API, best effort (the permission is asked on the first notification). |

**Send one.** Tool `infantry_notify` (MCP, scripts `ih.infantry_notify{...}`, CLI):

```
infantry notify --title "Release cut" --body "1.1.0 is tagged" --level milestone --sound chime
infantry notify --title "Wake up: the nightly build failed" --level urgent --sound alarm --ticket ticket-1234abcd
```

Arguments: `title` (required), `body`, `level` (`info`, `milestone`, `urgent`), `sound`, `target` (`all`, `desktop`,
`android`, `web`), `ticketId`, `url`. A notification sent while no client is connected waits in the daemon
(pending.json in the plugin's state folder) and is delivered on the next connect; each client acks what it showed.

**Milestone tickets.** `infantry_notify_milestone {ticketId, on, sound}` marks a ticket (the notifyOnDone flag; the mark
is the plugin's, kept in milestones.json in the plugin's state folder, not a field of the ticket). When the marked ticket
enters done, ONE `milestone` notification fires. CLI: `infantry notify --on-done <ticket> [--sound bell] [--off]`.

**Sounds.** Built in: `chime`, `bell`, `alarm` (synthesised by the plugin, no third-party audio). Add your own `.wav`,
`.mp3` or `.ogg` to `<state dir>/plugin-data/Infantry_Notify/sounds/` and use its file name; `none` is silent. The
"Notification sounds" card in Settings lists them with a Play button.

**Settings** (all `notify.*`, "Notifications" card): `notify.enabled` (master switch), `notify.quietHours`
(`HH:mm-HH:mm`, empty = off; inside it only `urgent` plays a sound), `notify.defaultSound` and `notify.milestoneSound`
(default `none`), `notify.queueMaxAgeMinutes` (1440), `notify.queueMax` (50), `notify.maxSoundBytes` (512 KB).

Scripting examples: [Scripting](Scripting.md#worked-examples).

## Run tab commands as tools (Infantry_Run)

**Commands are per project.** A saved command belongs to one project (stored with its `project` id in the daemon's
`commands.json`), and its `cwd` is relative to that project's registered folder (`infantry_project_config_set` with
`directory`, or the Git tab's / Run tab's "Set project folder"): blank or `.` is the folder itself, an absolute path inside
the folder is stored as the relative path, and anything outside the folder (`..`, another absolute path, a link that leaves
it) is refused. The Run tab lists only the selected project's commands, and switching projects switches the list. When the
selected project has no folder, the tab says so with a "Set project folder" action (the platform's folder chooser, else a
path field); its commands still load and list, but cannot start until the folder is set. Command ids are unique across
projects: a save never overwrites another project's command.

Migration: a command saved before commands were per project (no `project`) is assigned, when the daemon starts, to the
project whose folder contains its working directory (the deepest folder wins), its `cwd` rewritten relative to it. One that
no project folder contains stays visible under **Unassigned** in every project's Run tab, with "Assign to &lt;project&gt;";
it is never deleted silently.

The Run tab's saved commands are also daemon tools of `plugins/official/Infantry_Run/daemon`, so agents and Lua scripts can use them:
`infantry_run_commands` (list: the project's commands and the unassigned ones), `infantry_run_command_save` (id, name, `argv`
or a shell `command`, `cwd` relative to the project's folder), `infantry_run_command_delete`, `infantry_run_start` (returns the
run id) and `infantry_run_stop`. Every call acts in a project: its `project` argument, else the caller's own; delete and start
refuse another project's command. They run through the same `CommandStore` and `RunManager` as the frames and are gated by the
same actions (`commands.read`, `commands.save`, `runs.start`, `runs.stop`). A call that names a `project` other than the
caller's is refused unless `allowCrossProject` is true.

CLI: `infantry run-cmd list | save <id> <cwd|.> "<command line>" [--name N] | delete <id> | start <id> | stop <runId> [--force]`
(add `--project <id> --cross-project` to act on another project). Lua: `ih.call("infantry_run_command_save", {id = "site", command = "npm run build"})`.
