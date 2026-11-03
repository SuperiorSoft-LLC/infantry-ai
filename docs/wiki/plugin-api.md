[← Wiki index](index.md)

# UI plugin API

This page lists every type and function a UI plugin uses. It is written for a developer who has never seen the code.
For a step-by-step tutorial, read [Make a plugin](make-a-plugin.md). For the daemon half, read
[Daemon plugin API](daemon-plugin-api.md).

Two Gradle modules make up the UI plugin API:

| Module | Source folder | What it holds |
|---|---|---|
| `infantryPlugins` | `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/` | Data only, no UI: `plugin.api` (the `module.json` model, services, config declarations) and `plugin.host` (the loader rules). |
| `infantryUiCore` | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/` | The Compose side: `UiPlugin`, `UiRegistrar`, tab and panel providers, block types, config values. |

A plugin jar compiles against `infantryUiCore` with `compileOnly` and ships only its own classes. The app provides
the API, Compose and the Kotlin runtime when it loads the jar. The template build file shows this:
`plugins/_template/build.gradle.kts`.

**The rule behind all of it:** whatever your code registers must also be declared in `module.json`, under the same id
or name. The host refuses anything that is not declared, and anything declared but not registered, and lists each
refusal as one line (Settings > Plugins, and `TabFactory.problems`). Nothing is dropped silently.

## 1. Entry point: `UiPlugin` and `UiRegistrar`

Source: `infantryUiCore/.../ui/plugin/TabFactory.kt`.

```kotlin
interface UiPlugin {
    fun register(registrar: UiRegistrar)
    fun stop() {}        // release threads, timers, sockets; the host calls it when it unloads the module
}
```

`module.json` `entry` names a class that implements `UiPlugin` and has a public no-argument constructor. The host
calls `register` once per load.

`UiRegistrar` is what `register` receives. Every member below is a place to hand the host something. Every member
except `services`, `tab`, `interceptor`, `block` and `observer` has an empty default body, so a host that does not
support it ignores the call.

| Member | Registers | Declare in `module.json` |
|---|---|---|
| `val services: PluginServices` | (read-only) the services you may use | `services` |
| `tab(TabProvider)` | a tab in a strip | `tabs` |
| `panel(PanelProvider)` | a section in a side panel or a named slot | `panels` |
| `interceptor(name, TabInterceptor)` | a hook on a tab | `interceptors` |
| `block(name, SlotBlock)` | content above or below a tab container | `blocks` |
| `observer(name, (PluginEvent) -> Unit)` | an event listener | `observers` and `events` |
| `shell(name, BlockOverride)` | a replacement or wrapper for a shell node | `shell` |
| `inject(name, BlockInjection)` | a block added among a node's children | `inject` |
| `fill(node, Block)` | a tree for an empty shell slot | `fills` |
| `tabTree(tab, Block)` | the block tree a tab draws | (core use) |
| `implementation(registry, key, value)` | an implementation for an `implementation` config field | `implementations` |
| `blockType(type, BlockType)` | a block type for config trees | `blockTypes` |
| `fieldType(id, FieldType)` | a new config field type | `fieldTypes` |
| `sessionBadge(SessionBadgeProvider)` | a badge on every session row | (none) |

A complete `register`:

```kotlin
class NotesPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        registrar.services.get(Services.NOTIFICATIONS)?.notify("Acme_Notes", "loaded")
        registrar.tab(TabProvider(
            id = "acme.notes", container = TabContainers.CENTER, order = 120,
            title = { "Notes" },
            icon = tabIcon("acme-notes", { circle(12f, 12f, 8f) }),
            testTag = "center-tab-ACME_NOTES",
        ) { NotesTab(it) })
    }
}
```

## 2. Tabs

Source: `ui/plugin/TabFactory.kt`.

```kotlin
data class TabProvider(
    val id: String,                    // unique across modules, "vendor.name"
    val container: String,             // TabContainers.CENTER or TabContainers.RIGHT
    val order: Int,                    // lower first; core tabs leave gaps of 10
    val title: (AppState) -> String,   // read from state, so a count stays live
    val icon: ImageVector,             // a drawn vector, never a text glyph (web renders those as tofu)
    val testTag: String,
    val parent: String? = null,        // the host tab this one is a sub-tab of
    val shortcut: Int? = null,         // Ctrl+<digit>
    val routes: List<String> = emptyList(),  // documentation only
    val module: String = "",           // set by the host; whatever you pass is ignored
    val content: @Composable (TabContext) -> Unit,
)
```

- `TabContainers.CENTER` and `TabContainers.RIGHT` are the two strips. A container is any string id.
- The tab id and its container must match the `module.json` `tabs` entry: `{ "id": "acme.notes", "container": "center" }`.
- A second tab with an id already taken is skipped. To replace another module's tab on purpose, use a
  `preferences` entry (see [Make a plugin](make-a-plugin.md), module.json reference).

### Icons

`tabIcon(name, vararg paths: PathBuilder.() -> Unit): ImageVector` and `PathBuilder.circle(cx, cy, r)` (source:
`ui/plugin/TabIcon.kt`) build a 24x24 vector from path commands:

```kotlin
val icon = tabIcon("acme-hello", { circle(12f, 12f, 9f) }, { moveTo(8.5f, 14f); curveTo(10f, 16f, 14f, 16f, 15.5f, 14f) })
```

### `TabContext`

Every tab, panel section and slot block receives a `TabContext`. It is the only door from your UI to the app.

| Member | Meaning |
|---|---|
| `state: AppState` | The window's read model (agents, tickets, timeline and so on). Read it in a composable and it recomposes. |
| `open(tabId)` | Show another tab by id, in its own container. |
| `showChat()` | Show the selected agent's chat. |
| `insertIntoChat(text)` | Put text into the chat box at the cursor. |
| `openAgent(sessionId)` | Select an agent and show its chat. |
| `diffTarget`, `onOpenDiff(target)` | The current diff target and a way to open one. |
| `transcript` | A composable that draws the selected agent's transcript (centre strip only; elsewhere it draws nothing). |
| `services: PluginServices` | Your module's gated services (section 6). |
| `config(nodeId): ConfigValues` | A node's resolved config values, live (section 5). |
| `subTab(hostId)`, `pickSubTab(hostId, subTabId)` | Which sub-tab is picked in a host tab. |
| `SubTabs(hostId)` | A `@Composable` that draws host tab `hostId`'s sub-tabs as a strip with the picked one's content. |

`TabContext.controller` is `internal`: no plugin reaches the window's controller. See Open questions.

### Sub-tabs

A tab whose `parent` names another tab is that tab's sub-tab. The host draws them with `context.SubTabs("host.id")`.
A host can offer a `children.grouped` checkbox on its own config node (`TabGroups.GROUPED`): true draws the
children as sub-tabs, false leaves them in their strip.

```kotlin
TabProvider(id = "acme.notes.list", container = TabContainers.CENTER, order = 10, parent = "acme.notes",
    title = { "List" }, icon = icon, testTag = "t") { Text("list") }
```

## 3. Side panels, slots and child plugins

### Panel sections

```kotlin
data class PanelProvider(
    val id: String,          // unique, "vendor.name"
    val panel: String,       // Panels.LEFT, Panels.RIGHT, Panels.LEFT_BOARD, or a host's slot name
    val order: Int,          // lower first
    val testTag: String,
    val fill: Boolean = false,   // take the panel's free height
    val module: String = "",     // set by the host
    val content: @Composable (TabContext) -> Unit,
)
```

`Panels.LEFT = "left"`, `Panels.RIGHT = "right"`, `Panels.LEFT_BOARD = "left.board"` (an empty slot right under the
sidebar's Board button), `Panels.TABS_ORDER = 100` (on the right, an order below 100 draws above the tab strip).
Declare it as `{ "id": "...", "panel": "left" }` in `panels`. A section naming a panel that nobody declared is
refused ("names unknown panel").

```kotlin
registrar.panel(PanelProvider(id = "acme.hello.greeting", panel = Panels.LEFT_BOARD, order = 10,
    testTag = "acme-hello-greeting-block") { Text("Hello") })
```

### Slots: how a plugin becomes a child

A **slot** is a named place that one plugin (the host) draws and other plugins fill.

1. The host declares the slot in `module.json`: `"slots": ["chat.feed"]`.
2. The host draws it. Source: `ui/plugin/ChildSlot.kt`.
3. A child module fills it with a `panels` entry whose `panel` is the slot name: `{ "id": "infantry.chatfeed", "panel": "chat.feed" }`.

`ChildSlot` signature:

```kotlin
@Composable
fun ChildSlot(
    name: String, state: AppState, controller: Controller, modifier: Modifier = Modifier,
    args: Map<String, Any?> = emptyMap(), context: TabContext? = null,
    empty: @Composable () -> Unit = { SlotEmpty(name) },
)
```

Sections draw top to bottom in `order`; a `fill` section takes the free height. With no section, `empty` draws (a
generic "Nothing is installed for <slot>." notice by default). The host passes `args`; a child reads them with
`LocalSlotArgs.current` (a `Map<String, Any?>`, empty outside a slot).

Real example: `Infantry_Chat` declares `"slots": ["chat.feed"]` and calls `ChildSlot("chat.feed", ...)` in
`plugins/official/Infantry_Chat/src/ChatPanel.kt`. `Infantry_ChatFeed` fills it
(`plugins/official/Infantry_ChatFeed/module.json`: `"panels": [{ "id": "infantry.chatfeed", "panel": "chat.feed" }]`)
and is therefore a child of `Infantry_Chat`.

`PanelSlot(panel)` (a `ColumnScope` function, same file area) is the other way to draw a slot, from inside a section
that a `PanelSections` call is drawing.

Because `controller` is internal to `TabContext`, an outside plugin cannot call `ChildSlot`. It can read a slot's
sections and draw them itself:

```kotlin
LocalTabFactory.current.panels("acme.notes.extra").forEach { section -> section.content(context) }
```

`LocalTabFactory` is in `ui/App.kt`; `TabFactory.panels(panel)` returns the sections in order. The example in
[Make a plugin](make-a-plugin.md) compiles this form.

## 4. Interceptors, slot blocks, observers, badges

### `TabInterceptor`

```kotlin
class TabInterceptor(
    val title: ((state: AppState, proceed: () -> String) -> String)? = null,
    val before: (@Composable (TabContext) -> Unit)? = null,
    val after: (@Composable (TabContext) -> Unit)? = null,
    val content: (@Composable (context: TabContext, proceed: @Composable () -> Unit) -> Unit)? = null,
)
```

Bound to a tab by `module.json` `interceptors`: `{ "target": "infantry.epics", "name": "hi", "kind": "around" }`.
`InterceptorKinds.BEFORE` draws only `before`, `AFTER` only `after`, `AROUND` (default) runs every part. A part the
kind does not run is logged and skipped; an unknown kind refuses the interceptor.

```kotlin
registrar.interceptor("hi", TabInterceptor(title = { _, proceed -> proceed() + " +hi" }))
```

### `SlotBlock`

`SlotBlock(content: @Composable (TabContext) -> Unit)` adds content above or below a whole tab container. The slots
are `Slots.CENTER_TOP`, `Slots.CENTER_BOTTOM`, `Slots.RIGHT_TOP`, `Slots.RIGHT_BOTTOM` (`"<container>.top"` and
`"<container>.bottom"`; `Slots.ALL` lists them). Declare `{ "slot": "center.top", "name": "banner", "order": 0 }` in
`blocks`; register `registrar.block("banner", SlotBlock { Text("hi") })`. Any other slot name is refused.

### Observers and events

```kotlin
registrar.observer("count") { e: PluginEvent -> println(e.data["id"]) }
```

Declare `{ "event": "tab.opened", "name": "count" }` in `observers` and list the event in `events`. The host
unsubscribes your observers when it unloads the module. Event names are in `Events` (section 6).

### Session badges

```kotlin
registrar.sessionBadge(SessionBadgeProvider(id = "acme.badge", order = 0) { state, agent ->
    if (agent.id.isNotEmpty()) SessionBadge("acme", BadgeTone.INFO, "Acme tooltip") else null
})
```

`SessionBadge(label, tone, tooltip, testTag)`; `BadgeTone` is `INFO, MUTED, ACCENT, OK, WARN, DANGER`. The provider
returns one badge per session row or null. It reads live state.

## 5. Config values and the config tree

A module declares config in `module.json` `config` (see [Make a plugin](make-a-plugin.md)). Your code reads values:

- In a tab, panel or slot block: `context.config("<node id>")` returns a `ConfigValues`.
- In a block (`BlockScope`): `scope.config()` returns the values of the block's own node.

`ConfigValues` (source: `ui/shell/ShellContext.kt`, near line 942):

| Member | Returns |
|---|---|
| `keys` | the set of keys |
| `string(key)`, `int(key)`, `double(key)`, `boolean(key)` | typed value, or null when unset or another type |
| `strings(key)` | a `multiSelect` as `List<String>` |
| `classes(slot, key = "classes")` | the style tokens of a `classes` field's slot |
| `color(key)` | `0xAARRGGBB` as `Long` |
| `overridden(key)` | true when the user set the key, false when it resolves from a default |
| `json(key)` | the raw JSON |
| `obj(key)`, `list(key)` | a `group` field as `ConfigValues`; a `list` of objects as one per item |

`configAs<T>()` decodes the values into a `@Serializable` class by property name. `EmptyConfig` is the value with
no keys (outside a shell, and in tests). A missing value is a value, not an error: fall back to your constants.

Values update live; reading inside a composable recomposes it when the user changes a setting.

A community module's `context.config(node)` can read only nodes it owns or was granted; other nodes read as empty
unless `modules.json` grants `config.read` (`TabFactory.configReadAllows`, `modulesConfig.configReadGate`).

### Registering a new field type

```kotlin
interface FieldType {
    fun validate(field: ConfigFieldDeclaration, value: JsonElement?): String? = null   // null = acceptable
    @Composable fun Editor(field: ConfigFieldDeclaration, value: JsonElement?, enabled: Boolean, onChange: (JsonElement?) -> Unit)
}
```

Source: `ui/plugin/config/FieldTypes.kt`. Declare the id in `fieldTypes`, register with
`registrar.fieldType("acme.slider", MyType)`. A built-in id, a repeat id or an undeclared id is refused.

### Implementations

`registrar.implementation("chat.feed.renderers", "user", renderer)` stores any object under a registry and key.
Declare `{ "registry": "chat.feed.renderers", "key": "user" }` in `implementations`. A config field of type
`implementation` with `registry` set picks one key. `Infantry_ChatFeed` registers its row renderers this way.

## 6. Platform services

Source: `infantryPlugins/.../plugin/api/Services.kt`. Get a service with `registrar.services.get(Services.X)`, or
`context.services.get(Services.X)` in a tab. It returns null when the module did not declare it, the owner denied
it, or the platform lacks it. A community module also needs each service under `allow` in `modules.json`.

| Key | Type | What it gives |
|---|---|---|
| `Services.NOTIFICATIONS` | `Notifications` | `notify(title, body)`: a log line and, where the host has one, a toast. |
| `Services.STORAGE` | `PluginStorage` | `read(name)`, `write(name, text)`, `list()`: your own data area, plain file names, no access to other modules. |
| `Services.SETTINGS` | `PluginSettings` | `get(key)`, `set(key, value)`: your own key/value settings. |
| `Services.EVENTS` | `EventBus` | `publish(PluginEvent)`, `subscribe(name, observer)`; the returned function unsubscribes. |
| `Services.DAEMON` | `DaemonAccess` | `suspend call("GET /health", body)`; only the routes in `module.json` `routes`. Returns `DaemonReply.Ok(status, body)`, `Refused(reason)` or `Failed(reason)`. |
| `Services.ENDPOINTS` | `EndpointAccess` | `suspend call(endpointId, method, path, body, headers)` to a backend you declared in `endpoints`; the host adds the credential. Returns `EndpointReply.Ok / Refused / Failed`. Redirects are not followed. |

Events you can name in `events`: `Events.TAB_OPENED = "tab.opened"`, `Events.SESSION_SELECTED = "session.selected"`,
`Events.TICKET_CHANGED = "ticket.changed"`, `Events.MODULES_LOADED = "modules.loaded"`.

```kotlin
val daemon = context.services.get(Services.DAEMON)
val scope = rememberCoroutineScope()
Button(onClick = { scope.launch {
    when (val r = daemon?.call("GET /health", null)) {
        is DaemonReply.Ok -> println(r.body)
        is DaemonReply.Refused -> println("refused: ${r.reason}")
        is DaemonReply.Failed -> println("failed: ${r.reason}")
        null -> println("service not available")
    }
} }) { Text("Ping") }
```

## 7. Blocks, shell nodes, overrides and injections

The window is a tree of `Block` nodes (`CoreShell.root`). A plugin can fill, replace, wrap or add to nodes by id.
Node ids are API and are never renamed. The tree design is in `docs/BLOCKS-LAYOUT-DESIGN.md`.

### `Block`

Source: `ui/block/layout/Contract.kt`.

```kotlin
abstract class Block(val blockType: String, val id: String, val place: Place = Place(), val children: List<Block> = emptyList()) {
    @Composable abstract fun Render(scope: BlockScope)
    open fun withChildren(children: List<Block>): Block   // default refuses; a container overrides it
}
class BlockScope(val modifier: Modifier, val box: BoxScope?, val row: RowScope? = null, val blockId: String = "")
data class Place(val weight: Float? = null, val weightFill: Boolean = true, val size: Size = Size.Wrap, val align: Alignment? = null, val show: Show = Show.Always)
enum class Size { Wrap, FillMax, FillWidth, FillHeight }
enum class Show { Always, Wide, Compact }
```

A block applies `scope.modifier` exactly once, on its outermost node, and knows nothing of its parent. A refusal
throws `BlockRefusedException` (use `refuse("message")`); resolvers list it, nothing is dropped silently.

### Overrides (`shell`)

```kotlin
class BlockOverride(val content: @Composable (scope: BlockScope, inner: Block?) -> Unit)
registrar.shell("myBar", BlockOverride { scope, inner -> Box(scope.modifier) { inner?.Render(scope) } })
```

Declare `{ "node": "infantry.shell.footer", "name": "myBar", "mode": "wrap", "priority": 0 }` in `shell`. Modes:
`replace` (core block not drawn), `wrap` (you draw around it; it arrives as `inner`), `before` and `after` (your block
is drawn as a sibling). `node` may be an id or a path selector like `infantry.shell.left/infantry.board.button`, with
`[n]` for the n-th child (source: `NodeSelector` in `ui/block/layout/Resolver.kt`). Higher `priority` wins a replace.
A community module needs the node in `allowOverride` in `modules.json`. A locked node (`CoreShell.locked`, or a node
a module marked `overridable: false` in `nodes`) cannot be overridden by anyone.

### Injections (`inject`)

```kotlin
class BlockInjection(val place: Place = Place(), val content: @Composable (scope: BlockScope) -> Unit)
```

Declare `{ "into": "infantry.shell.left", "name": "badge", "at": "last", "order": 0 }` in `inject`. `at` is `first`,
`last`, `before:<child id>` or `after:<child id>`. The child's id is `<module>.<name>`. A community module needs the
target in `allowInject`.

### Block types

```kotlin
fun interface BlockType { fun create(id: String, children: List<Block>): Block }
registrar.blockType("acme.banner", BlockType { id, kids -> BannerBlock(id, kids) })
```

Declare the type in `blockTypes` (`type`, `title`, `fields`, `takesChildren`, `needs`, `parents`, `max`,
`template`). Source: `ui/block/layout/BlockTypes.kt`. Core's own types are `layout.group`, `layout.row`,
`layout.column`, `layout.stack`, `layout.spacer`. `needs` is one of `any | row | box | column` (`BlockNeeds`).
A type registered but not declared, or declared by two modules, is refused.

### Fills

`registrar.fill(nodeId, block)` puts a tree into an empty shell slot. Declare the node in `fills` (a plain id, or
`{ "node": "...", "layout": "layout/page.json", "replaces": "Other_Module" }`). `Infantry_Chat` (official) fills
`infantry.shell.center`. The `ModuleManifest` comment says fills are for the core tier and that a community fill is
only a `layout` composition whose node is in the module's `allowOverride` grant; see Open questions.

## 8. The loader types (read-only information)

You rarely use these in a plugin; they explain the status you see in Settings > Plugins.

- `ModuleManifest` (`plugin/api/ModuleManifest.kt`): the whole `module.json` model. The field list is in
  [Make a plugin](make-a-plugin.md).
- `Tier` is `CORE`, `OFFICIAL`, `COMMUNITY`, decided by the folder, never by the file.
- `ModulesConfig` (`plugin/api/ModulesConfig.kt`): `<plugins root>/modules.json`: `folders`, `modules` (per module:
  `enabled`, `allow`, `deny`, `allowOverride`, `allowInject`, `endpoints`), `demoLock`, `configReadGate`.
- `ModuleState` is `LOADED`, `DISABLED`, `LOCKED`, `REFUSED`; `ModuleStatus` carries the state and the reason.
- `PLUGIN_API_VERSION = 2`. A module whose `apiVersion` differs is refused. `PLUGIN_API_REVISION = 2` is the additive
  revision (1 added the `official` tier, 2 added sub-tabs).
- `registerModule(manifest, tier, services, plugin): InfantryModule` runs a plugin's `register` into a module value.
  Tests use it (the template's `StarterPluginTest.kt`).
- `TabFactory(modules)` binds modules, rejects what is not declared and lists problems in `factory.problems`. Tests
  assert `factory.problems == emptyList()`.
- Module names must match `^[A-Z][A-Za-z0-9]*_[A-Z][A-Za-z0-9]*$` and equal the folder name
  (`plugin/host/ModuleResolver.kt`).

## Open questions

- The `ModuleManifest.fills` comment says "Core tier only", yet `Infantry_Chat` (official) declares `fills`. This page does not know which tiers the loader admits for code fills; the loader rules are in `plugin/host/ModuleResolver.kt` and `TabFactory`.
- `TabContext.controller` is `internal`, so a plugin outside the UI core cannot call `ChildSlot` (it needs a
  `Controller`). The only public way to draw a slot from a tab is `LocalTabFactory.current.panels(slot)` and calling
  each section's `content`, which skips `ChildSlot`'s `LocalSlotArgs` and empty-state handling. Whether a public way to
  call `ChildSlot` should exist is not stated in the code.
- `TabContext.routes` and `TabProvider.routes` are documented as "documentation"; nothing in the code checks them.
- `ModuleManifest.tier` is read but ignored. Some shipped manifests still carry it.
- `module.json` `buildOrder` is read by the Gradle module-list generator (`superiorSoftLibrary/infantryAppUi/build.gradle.kts`)
  but is not a field of `ModuleManifest`; see [Make a plugin](make-a-plugin.md).
