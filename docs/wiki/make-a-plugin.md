[← Wiki index](index.md)

# Make a plugin

This page takes you from an empty folder to a working plugin with a UI half and a daemon half, then shows a child
plugin that fills the first one's slot. An outside developer who has never seen the code can follow it.
The example at the end was compiled with Gradle on the dev head to prove it builds.

Reference pages: [UI plugin API](plugin-api.md), [Daemon plugin API](daemon-plugin-api.md). Overview of tiers and
trust: [Plugins](Plugins.md).

## 1. What a plugin is

A plugin (also called a module) is a folder named `Vendor_Module`. Both words start with a capital letter and there is
one underscore (`^[A-Z][A-Za-z0-9]*_[A-Z][A-Za-z0-9]*$`). The folder holds a `module.json` and up to three halves, each
optional:

| Half | Declared by | Runs in | Code |
|---|---|---|---|
| UI | `entry` + `jar` | the app window | a Kotlin/Compose class implementing `UiPlugin` |
| Daemon | `daemon.entry` + `daemon.jar` | the daemon | a class implementing `DaemonPlugin` |
| Harness | `harness.*` | the daemon | a `HarnessPlugin` (see [Harnesses and providers](Harnesses.md)) |

The folder also decides the **tier**: `core/` (compiled in), `official/` (signed, made by the app's developers),
`community/` (yours). Put your plugin in `plugins/community/`. A folder starting with `_` is never loaded.

## 2. Start from the template

`plugins/_template` is a working starter (one tab, one panel section, one interceptor, one daemon tool, one test).

```
cp -r plugins/_template plugins/community/Acme_Notes
```

Then edit: `name`, `jar`, `entry`, `daemon.entry`, `daemon.jar`, `author`, `license` in `module.json`; the package
name; the daemon jar name in `daemon/build.gradle.kts` (`archiveFileName`). The build finds any
`plugins/community/*/build.gradle.kts` by scanning in `settings.gradle.kts`; nothing else names your module.

## 3. `module.json`, every field

JSON has no comments, so each field is explained here. Unknown keys are ignored. The model is
`ModuleManifest` in `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/ModuleManifest.kt`.

### Identity

| Field | Default | Meaning |
|---|---|---|
| `name` | required | `Vendor_Module`; equals the folder name. A bad name, a folder of another name or a repeat is refused. |
| `version` | required | Semver. Other modules' `dependencies` ranges are checked against it. |
| `apiVersion` | `2` | The plugin API revision this build speaks. A different value refuses the module. |
| `author` | none | Required for community. Shown on the module's tabs and in Settings. |
| `license` | none | Required for community: an SPDX id or `NOASSERTION`. |
| `description` | `""` | One line. |
| `tier` | none | Ignored. The folder decides; a mismatch is logged. |
| `platforms` | all | `["desktop","android","web"]`. A host on another platform skips the module ("not available on this platform"); an unknown value refuses it. |
| `buildOrder` | none | Used only by the Gradle module-list generator for modules **compiled in** (core and official): it sorts the generated list, then by name. It is not a `ModuleManifest` field; the loader of a community jar ignores it. Source: `superiorSoftLibrary/infantryAppUi/build.gradle.kts`. |

### Loading and relations

| Field | Default | Meaning |
|---|---|---|
| `dependencies` | `[]` | `[{ "id": "Infantry_Git", "version": ">=1.0 <2" }]`. Modules this one cannot run without. Missing, disabled, refused or out of range refuses this module; Settings will not enable it until they are enabled. Each also loads first. `version` defaults to `*`. Range syntax: `>= > <= < =`, `1.2`, `^1.2.3`, `~1.2.3`, `*`, `\|\|`. |
| `sequence` | `[]` | Load order only: modules that, if loaded, load before this one. An absent or off name is ignored. |
| `parents` | `[]` | Modules this one is a child of, in addition to the owners of the slots its `panels` fill. Shown in the Plugins tab as a tree. |
| `entry` | none | The `UiPlugin` class (public no-arg constructor). Required for a community UI half. |
| `jar` | none | The built jar, relative to the module folder; it must stay inside the folder. |

### Services and permissions

| Field | Meaning |
|---|---|
| `services` | Platform services you use: `notifications`, `storage`, `settings`, `events`, `daemon`, `endpoints`. An unlisted service is `null` in your code. A community module also needs each in `allow` in `modules.json`. |
| `routes` | `["GET /health"]`: the only daemon routes the `daemon` service may call. |
| `endpoints` | `[{ "id": "echo", "url": "http://localhost:8080/**", "methods": ["GET","POST"], "auth": { "kind": "bearer", "field": "apiKey" } }]`. Backends you call by id. `url` is one origin plus a path pattern (`*` one segment, `**` the rest); https only (http for localhost, 127.0.0.1, 10.0.2.2). `auth.kind` is `none`, `bearer`, `header` (with `name`) or `basic`; `field` is the keystore field. The owner may override the URL per install. |
| `events` | Events you may publish or observe: `tab.opened`, `session.selected`, `modules.loaded`, `ticket.changed`. |
| `actions` | `[{ "id": "chat.prompt", "label": "Message an agent", "kind": "write", "frames": ["prompt"] }]`. Named actions your controls perform, so a policy preset can switch them off. |
| `nodes` | `[{ "id": "acme.notes.main", "overridable": false }]`. Locks a node id you draw against replace, wrap, beside and inject. A community module may lock only ids under its own name (`Acme_Notes.`). |

### UI contributions

| Field | Entry shape | Registered with |
|---|---|---|
| `tabs` | `{ "id": "acme.notes", "container": "center" }` (container `center` or `right`) | `registrar.tab` |
| `panels` | `{ "id": "acme.hello.greeting", "panel": "left.board" }`; `panel` is `left`, `right`, `left.board` or a slot name | `registrar.panel` |
| `slots` | `["acme.notes.extra"]`: named places you draw and others fill | (you draw it) |
| `preferences` | `{ "replace": "infantry.git", "with": "acme.git" }`: your tab or section takes another's place (id, container, order, shortcut, tag). Community needs `allowOverride`. | the replacing tab or panel |
| `interceptors` | `{ "target": "infantry.epics", "name": "hi", "kind": "before\|after\|around" }` | `registrar.interceptor` |
| `observers` | `{ "event": "tab.opened", "name": "count" }` | `registrar.observer` |
| `blocks` | `{ "slot": "center.top", "name": "banner", "order": 0 }`; slots `center.top`, `center.bottom`, `right.top`, `right.bottom` | `registrar.block` |
| `shell` | `{ "node": "infantry.shell.footer", "name": "myBar", "mode": "replace\|wrap\|before\|after", "priority": 0 }` | `registrar.shell` |
| `inject` | `{ "into": "infantry.shell.left", "name": "badge", "at": "first\|last\|before:<id>\|after:<id>", "order": 0 }` | `registrar.inject` |
| `fills` | `"infantry.shell.center"` or `{ "node": "...", "layout": "layout/page.json", "replaces": "Other_Module" }` | `registrar.fill` |
| `blockTypes` | `{ "type": "acme.banner", "title": "Banner", "fields": [...], "takesChildren": false, "needs": "any", "parents": [], "max": null, "template": false }` | `registrar.blockType` |
| `fieldTypes` | `["acme.slider"]` | `registrar.fieldType` |
| `implementations` | `{ "registry": "chat.feed.renderers", "key": "user" }` | `registrar.implementation` |

### `config`

Config cards render in Settings with no UI code from you. Each entry is a `ConfigDeclaration`
(`plugin/api/NodeConfig.kt`):

| Key | Meaning |
|---|---|
| `node` | The node id the fields belong to (a tab or section id you own). |
| `group` | Group name (default `General`). |
| `tab` | The Settings tab the card appears in (`Plugins`, `Appearance`, ...). Null keeps the older Plugins view. |
| `order` | Sorts cards inside a tab. |
| `title` | The card heading (default `group`). |
| `applies` | The "Applies to:" line. |
| `keywords` | Extra words the settings search matches. |
| `fields` | The fields (below). |
| `block` | The id of a fill that draws the card when it is not a form (then no `fields`). |
| `locked` | A trust section only core may declare. |
| `readOnly` | Set by the host, never trusted from `module.json`. |
| `platforms` | The platforms the group exists on; null = all. |

A field (`ConfigFieldDeclaration`): `key`, `type`, `label`, `default`, `options`, `min`, `max`, `step`, `registry`,
`exposed`, `autoSelectNew`, `optionLabels`, `description`, `order`, `scope`, `platforms`, `slots`, `item`, `required`,
`pattern`, `patternMessage`, `minLength`, `maxLength`, `visibleWhen` (`{ "key": "...", "eq": "..." }`), `placeholder`,
`palette`, `display` (`"dropdown"` draws a `select` as a menu), `fields` (for `group`, and for a `list` of objects).

Built-in field types (`ConfigFieldTypes`): `text`, `textarea`, `number`, `checkbox`, `toggle` (an alias of
`checkbox`), `select`, `radio`, `multiSelect`, `implementation`, `color`, `classes`, `list`, `group`, `path` (a folder)
and `file` (a file, narrowed by the field's `extensions`, e.g. `["ihq"]`). A `path` or `file` field is drawn with the
shared `PathField`: a text box plus Browse, the platform's native chooser (no Browse on web and Android, where paths
are typed). A module can add more with `fieldTypes`.

**Scopes.** A field's `scope` says where the user's override lives: `device` (this host's UI preferences), `daemon`
(the daemon; the daemon half reads it with `api.config(key)` and only `daemon` fields are served), or none (the
default `node_config` rows in the daemon). A value resolves to the user's stored value at the node or its parent,
else your `default`. Your code reads it with `context.config("<node>")` (see [UI plugin API](plugin-api.md)).

Web and Android compile config in. For a plugin that keeps a Kotlin copy of its config, keep a test that fails when
the copy differs from `module.json` (`AcmeHelloConfigTest` in `plugins/community/Acme_Hello`).

### `daemon`

The daemon half (class `DaemonCapabilities`):

| Key | Meaning |
|---|---|
| `entry`, `jar` | The `DaemonPlugin` class and its jar. Both null = no daemon code (the other keys are then ignored). |
| `apiAtLeast` | The lowest daemon API version your code needs (semver). A newer requirement than the daemon's refuses the module. |
| `tools` | `{ "name", "action", "kind": "read\|write\|destructive\|spawn", "description" }` per tool. |
| `frames` | `{ "type", "action", "kind", "ownerOnly" }` per client frame. |
| `hooks` | Hook kinds you register: `frameGate`, `httpGate`, `admission`, `mailGate`, `incomingMail`, `spawnGate`, `ready`, `turns`, `sessions`, `policyProvider`, `connected`, `status`, `approvalRules`. |
| `routes`, `publicRoutes` | Absolute routes (core and official only). Relative routes need no declaration. |
| `env`, `secrets` | Environment variables and keystore secrets you may read. |
| `limits` | `callTimeoutMs`, `toolTimeoutMs`, `cpuMsPerCall`, `routeRequestsPerMinute`, `memoryMbHint`; unset = the global default. |
| `storage` | `{ "adopt": [...] }`: core tables an official module takes over. A community module declaring any is refused. |
| `config` | Daemon-side config cards, same schema as `config`; only `scope:"daemon"` fields are served. |
| `needs`, `wants` | Daemon capabilities the module needs (refused = it does not work) and wants (refused = it degrades). |

Details of each: [Daemon plugin API](daemon-plugin-api.md).

### `harness` and `agents`

`harness` (`jar`, `contract`, `ids`, `aliases`, `cli`, `icon`, `settings`, `hooks`) is covered in
[Harnesses and providers](Harnesses.md). `agents` lists agent definitions contributed to the daemon's agent
registry as data, no jar needed: `id`, `name`, `description`, `instructions`, `role`, `profile`, `harness`, `model`,
`contextLimit`, `effort`, `tools`, `schedule`, `ui` (`AgentDefinition`, `plugin/api/AgentDefinition.kt`). A community
module needs the grant `agents.contribute`. The reference is `plugins/examples/Acme_NewsAgent`.

## 4. The UI half

Implement `UiPlugin`, register everything `module.json` declares. The full API is in [UI plugin API](plugin-api.md).
Rules:

- Registered but not declared: refused. Declared but not registered: listed as a problem and skipped.
- Reach the app only through `TabContext` and the services in `registrar.services`.
- Draw an icon with `tabIcon` (a vector), never a text glyph.
- Read config with `context.config("<node>")`; fall back to constants when a key is unset.

## 5. The daemon half

Implement `DaemonPlugin`; register tools, routes, frames and hooks in `start`. Give it its own Gradle project at
`plugins/community/Acme_Notes/daemon/build.gradle.kts` with `compileOnly(project(":superiorSoftLibrary:infantryDaemonApi"))`.
The root `settings.gradle.kts` includes every `plugins/<folder>/<Module>/daemon` that has a build file as
`:daemon-plugin-<folder>-<Module>`. Details and limits: [Daemon plugin API](daemon-plugin-api.md).

## 6. Slots and child plugins

A **slot** is a named place a plugin draws and other plugins fill. A plugin that fills another plugin's slot is that
plugin's **child**.

- The host lists the slot in `slots` (`"slots": ["chat.feed"]`) and draws it.
- The child declares a `panels` entry whose `panel` is the slot name.
- The child should also list the host in `dependencies`, so it never loads without it.
- The Plugins tab shows the child under the host (`PluginTree`, `plugin/host/PluginTree.kt`: a module is a child of P
  when it lists P in `parents` or fills a slot P declares). A child shared by two parents cannot be switched off
  while one of them is enabled.

The real example: `Infantry_Chat` declares `"slots": ["chat.feed"]` and draws it with `ChildSlot("chat.feed", ...)`
(`plugins/official/Infantry_Chat/src/ChatPanel.kt`). `Infantry_ChatFeed` declares
`"panels": [{ "id": "infantry.chatfeed", "panel": "chat.feed" }]` and registers
`PanelProvider(id = "infantry.chatfeed", panel = "chat.feed", order = 0, fill = true)`. That makes `Infantry_ChatFeed` a
child of `Infantry_Chat`.

A host that is itself a community plugin cannot call `ChildSlot` (it needs a window controller, which is internal);
the example below draws the slot's sections itself. See [UI plugin API](plugin-api.md) section 3.

## 7. Registration paths

There are two paths. Your `register` code is the same for both.

1. **Run-time jar (desktop, headless daemon).** The loader reads `module.json`, loads `jar` in a filtered class
   loader (it sees the plugin API, Compose and Kotlin, nothing else of the app), instantiates `entry` and calls
   `register`. This is the path for every community plugin.
2. **Compiled in (web and Android).** Those platforms cannot load a jar at run time. The app UI module generates a
   module list at build time from `plugins/core/*/module.json` and `plugins/official/*/module.json` (the
   `generateModuleList` task in `superiorSoftLibrary/infantryAppUi/build.gradle.kts`), sorted by `buildOrder` then
   name, and compiles it in. On this path an entry class implements `ModuleSource`
   (`fun modules(): List<InfantryModule>`) and returns an `InfantryModule` whose manifest mirrors `module.json`.
   `Infantry_ChatFeed` serves both paths with one class: `class ChatFeedPlugin : UiPlugin, ModuleSource`
   (`plugins/official/Infantry_ChatFeed/src/ChatFeedPlugin.kt`). A module with no UI part (only `daemon` or
   `harness`) is left out of the generated list. On the JVM the sources are found with `java.util.ServiceLoader`
   (`META-INF/services/com.superiorsoftllc.infantry.ui.plugin.ModuleSource`); the web entry calls
   `CompiledModules.install(...)`.

A community plugin loaded from a jar works on the desktop app and the headless daemon only. To appear on web or
Android a plugin must be an official or core module compiled into the build.

## 8. Build

```
./gradlew :plugins:community:Acme_Notes:jar :daemon-plugin-community-Acme_Notes:jar
```

- UI jar: `plugins/community/Acme_Notes/build/libs/Acme_Notes.jar`. The name must equal `module.json` `jar`; the
  template sets it with `tasks.jar { archiveFileName.set("$moduleName.jar") }`.
- Daemon jar: `plugins/community/Acme_Notes/daemon/build/libs/Acme_Notes-daemon.jar`.
- The jars hold only your classes. Extra dependencies go in a fat jar or in `<module>/lib/*.jar`. Native files go in
  `<module>/native/<os>-<arch>/`.

## 9. Install, enable, disable

1. Put the folder (with `module.json` and the jars at the paths the file names) into `<plugins root>/community/`. The
   plugins root is `INFANTRY_PLUGINS_ROOT`, else `<state dir>/plugins`.
2. Enable it in Settings > Plugins > Modules, or edit `<plugins root>/modules.json`:

```json
{ "modules": { "Acme_Notes": { "enabled": true, "allow": ["notifications", "daemon.code", "daemon.tool"] } } }
```

3. Restart. A module that did not load shows a one-line reason in Settings > Plugins and in the log
   (`[plugins] Acme_Notes (community): refused - ...`).

Disable it with `"enabled": false` or in Settings. A module that another enabled module depends on cannot be turned
off first. A `.ihq` package installs the same way through the Plugins tab (`IhqInstaller`).

`allow` holds service names, `daemon.*` grants, `harness.*` grants and `agents.contribute`. `allowOverride` lists the
core contributions the module may replace; `allowInject` the node ids it may inject into; `deny` removes a service.

## 10. Test it

- **Unit test the registration.** `plugins/_template/src/test/kotlin/com/example/starter/StarterPluginTest.kt` parses
  `module.json`, calls `registerModule(manifest, Tier.COMMUNITY, NoPluginServices, YourPlugin())`, builds a
  `TabFactory(listOf(module))` and asserts `factory.problems == emptyList()` plus the tabs, titles and panels you expect.
  Run it with `./gradlew :plugins:community:Acme_Notes:test`.
- **Run it for real.** Start a throw-away instance so you never touch your own data:

```powershell
$env:INFANTRY_STATE_DIR = "$env:TEMP\plugin-test"; $env:INFANTRY_PORT = "8812"; $env:INFANTRY_PROVIDER = "stub"
.\gradlew.bat --no-parallel :superiorSoftLibrary:infantryUi:runRealDaemon
```

- **Read the status.** Settings > Plugins lists each module and each refusal with a reason. For a daemon half, the
  daemon's `GET /plugins` shows `failed: ...` for a disabled one.

## 11. A complete minimal example (built)

Two modules: `Acme_Notes` (a tab, a settings card, a slot, and a daemon tool) and `Acme_NotesExtra` (a child that fills
the slot). Both were compiled with:

```
./gradlew :plugins:community:Acme_Notes:jar :plugins:community:Acme_NotesExtra:jar :daemon-plugin-community-Acme_Notes:jar
```

Result on the dev head: `BUILD SUCCESSFUL`, and the jars `Acme_Notes.jar`, `Acme_Notes-daemon.jar` and
`Acme_NotesExtra.jar` were produced. The build files are copies of `plugins/_template/build.gradle.kts` and
`plugins/_template/daemon/build.gradle.kts` (only the daemon jar name changed). The unit test and a real run were
not part of this check; only the compile and jar steps.

### `plugins/community/Acme_Notes/module.json`

```json
{
  "name": "Acme_Notes",
  "version": "1.0.0",
  "apiVersion": 2,
  "author": "Acme (example)",
  "license": "NOASSERTION",
  "description": "Minimal example: a Notes tab with a settings card, a slot other plugins can fill, and a daemon tool.",
  "entry": "com.acme.notes.NotesPlugin",
  "jar": "build/libs/Acme_Notes.jar",
  "services": ["notifications"],
  "tabs": [{ "id": "acme.notes", "container": "center" }],
  "slots": ["acme.notes.extra"],
  "config": [
    { "node": "acme.notes", "group": "Notes", "tab": "Plugins", "order": 910, "title": "Notes (Acme_Notes)",
      "fields": [
        { "key": "heading", "type": "text", "label": "Heading", "default": "My notes", "order": 1 },
        { "key": "shout", "type": "checkbox", "label": "Shout", "default": false, "order": 2 }
      ] }
  ],
  "daemon": {
    "entry": "com.acme.notes.daemon.NotesDaemon",
    "jar": "daemon/build/libs/Acme_Notes-daemon.jar",
    "apiAtLeast": "1.0.0",
    "tools": [{ "name": "acme_notes_count", "action": "acme.notes.read", "kind": "read", "description": "How many sessions the daemon runs." }]
  }
}
```

### `plugins/community/Acme_Notes/src/main/kotlin/com/acme/notes/NotesPlugin.kt`

```kotlin
package com.acme.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.LocalTabFactory
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.circle
import com.superiorsoftllc.infantry.ui.plugin.tabIcon

const val NOTES_SLOT = "acme.notes.extra"

class NotesPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(
            TabProvider(
                id = "acme.notes", container = TabContainers.CENTER, order = 120,
                title = { "Notes" },
                icon = tabIcon("acme-notes", { circle(12f, 12f, 8f) }),
                testTag = "center-tab-ACME_NOTES",
            ) { NotesTab(it) },
        )
    }
}

@Composable
private fun NotesTab(context: TabContext) {
    val config = context.config("acme.notes")
    val heading = config.string("heading") ?: "My notes"
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("acme-notes")) {
        Text(if (config.boolean("shout") == true) heading.uppercase() else heading)
        Text("Agents: ${context.state.agents.size}")
        // Child modules fill this slot with `panels` entries whose "panel" is acme.notes.extra.
        LocalTabFactory.current.panels(NOTES_SLOT).forEach { section -> section.content(context) }
    }
}
```

### `plugins/community/Acme_Notes/daemon/src/main/kotlin/com/acme/notes/daemon/NotesDaemon.kt`

```kotlin
package com.acme.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NotesDaemon : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(object : DaemonTool {
            override val name = "acme_notes_count"
            override val description = "How many sessions the daemon runs."
            override val inputSchema = buildJsonObject { put("type", "object") }
            override fun call(call: ToolCall) = ToolResult("The daemon runs ${api.sessions.list().size} session(s).")
        })
        api.log("started")
    }
}
```

### `plugins/community/Acme_Notes/daemon/build.gradle.kts`

```kotlin
// DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the template's daemon half. Compiled against the daemon API only (compileOnly:
// the daemon provides it at run time). Built by the repo build as `:daemon-plugin-_template:jar`, so the starting point
// always compiles; a copy under plugins/<folder>/<Vendor_Module>/ builds as `:daemon-plugin-<folder>-<Vendor_Module>:jar`.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryDaemonApi"))
}

tasks.jar {
    // Must equal module.json's `daemon.jar` (relative to the module folder: daemon/build/libs/<this name>).
    archiveFileName.set("Acme_Notes-daemon.jar")
    from(layout.projectDirectory.file("../module.json"))
}
```

### `plugins/community/Acme_Notes/build.gradle.kts`

```kotlin
// PLUGINS-2 starter template. Copied to plugins/community/<Vendor_Module>/, settings.gradle.kts finds it by scanning
// and builds ONE jar named after module.json's "name" (build/libs/<Vendor_Module>.jar). The jar holds only this
// module's classes: the app provides the plugin API, Compose and the Kotlin runtime when it loads the jar.
plugins {
    kotlin("jvm")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(project(":superiorSoftLibrary:infantryUiCore"))
    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.material3)

    testImplementation(project(":superiorSoftLibrary:infantryUiCore"))
    testImplementation(compose.runtime)
    testImplementation(compose.foundation)
    testImplementation(compose.material3)
    testImplementation(kotlin("test"))
}

// The jar's name follows module.json, so a copy needs no edit here.
val moduleName: String = (groovy.json.JsonSlurper().parse(file("module.json")) as Map<*, *>)["name"] as? String
    ?: error("module.json has no name")

tasks.jar {
    archiveFileName.set("$moduleName.jar")
}

tasks.test {
    useJUnitPlatform()
}
apply(from = rootProject.file("gradle/ihq.gradle.kts"))
```

### `plugins/community/Acme_NotesExtra/module.json`

```json
{
  "name": "Acme_NotesExtra",
  "version": "1.0.0",
  "apiVersion": 2,
  "author": "Acme (example)",
  "license": "NOASSERTION",
  "description": "Child of Acme_Notes: fills its acme.notes.extra slot.",
  "dependencies": [{ "id": "Acme_Notes", "version": ">=1.0 <2" }],
  "entry": "com.acme.notesextra.NotesExtraPlugin",
  "jar": "build/libs/Acme_NotesExtra.jar",
  "panels": [{ "id": "acme.notesextra.hint", "panel": "acme.notes.extra" }]
}
```

### `plugins/community/Acme_NotesExtra/src/main/kotlin/com/acme/notesextra/NotesExtraPlugin.kt`

```kotlin
package com.acme.notesextra

import androidx.compose.material3.Text
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

class NotesExtraPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.panel(PanelProvider(id = "acme.notesextra.hint", panel = "acme.notes.extra", order = 10, testTag = "acme-notes-hint") {
            Text("A hint from Acme_NotesExtra.")
        })
    }
}
```

`Acme_NotesExtra/build.gradle.kts` is the same file as `Acme_Notes/build.gradle.kts`. <!-- docs-freshness: ignore -->

To try it, enable both modules in `modules.json` (`Acme_Notes` with `allow` `["notifications","daemon.code","daemon.tool"]`),
restart, open the Notes tab, and call `acme_notes_count` from an agent or `infantry mcp serve`.

## Open questions

- `plugins/_template/README.md` lists the `services` without `endpoints` and omits `slots`, `parents`, `config`,
  `fills`, `shell`, `inject`, `blockTypes` and `daemon`; this page is the complete list from the code.
- The template `module.json` sets `"dependencies": [{ "id": "Infantry_Git", ... }]`, so a copy of it does not load
  unless `Infantry_Git` is installed and enabled. Remove the line if you do not want that.
- `Acme_Hello`'s `module.json` carries `"tier": "core"` while it lives in `community/`. It is ignored, but it
  contradicts the folder.
- `buildOrder` appears in official and core manifests but is not part of the `ModuleManifest` model. Whether a
  run-time (community) loader should read it is not stated in the code.
- A community plugin cannot call `ChildSlot` (see [UI plugin API](plugin-api.md), Open questions).
- Packaging a community plugin for web or Android is not possible with the current build: the compiled-in list is
  generated only from `plugins/core` and `plugins/official` (and `plugins/demo` in a demo build).
