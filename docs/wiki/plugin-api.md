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
| `val scope: PluginScope` | (read-only) the plugin's lifetime: coroutines, timers, resources and cleanups that end on unload ([Plugin lifecycle](Plugin-lifecycle.md)) | (none) |
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

`ChildSlotRow(name, state, controller, modifier, args, context)` (same file) draws a slot as a ROW of small inline
blocks, each section at its own width, and draws nothing at all when no section fills it (no empty state, no layout
shift). Use it for a row of buttons inside a host's own layout.

#### The slots and event Infantry_Chat offers

The contract is Chat's (`plugins/official/Infantry_Chat/src/ChatSlots.kt`, declared in its `module.json`); a plugin
fills a slot with a `panels` entry and reads its arguments from `LocalSlotArgs.current`.

| Name | Kind | What a filler gets |
|---|---|---|
| `chat.composer.actions` | slot (row, beside attach) | `sessionId` (null in New agent mode), `draft` (the box's text), `sending` (a send is in flight) |
| `chat.message.actions` | slot (row over a message: hover on desktop/web, long-press on Android) | `sessionId`, `messageId` (the row's index in its session's transcript), `author` (`owner`, `agent`, `external`, `system`), `authorRole`, `authorAgent`, `text` (the WHOLE message, plain, markdown stripped; never a preview or a cut copy, so a plugin needs no second fetch) |
| `chat.message.header` | slot (small icons in every chat card's header row, left of the timestamp; give each a tooltip) | the `chat.message.actions` arguments plus `kind` (the row kind) |
| `chat.composer.insert` | event (declare it in your `events`, use the `events` service) | payload `sessionId?`, `text`, `mode` (`append` or `replace`), `send` (`true`/`false`) |

`chat.composer.insert` goes to the composer showing `sessionId`; with none, to the focused composer, else the one
showing the selected session. Append adds the text on a new line. `send: true` sends the draft only while the Chat
setting `chat.allowPluginSend` (Settings > Chat > Plugins in the composer) is on; it is off by default, so dictated
text is always reviewed first. `ComposerInsert(sessionId, text, replace, send).toEvent()` builds the event.

```kotlin
registrar.panel(PanelProvider(id = "acme.voice.mic", panel = "chat.composer.actions", order = 10, testTag = "acme-mic") {
    val draft = LocalSlotArgs.current["draft"] as? String
    MicButton(draft)
})
registrar.services.get(Services.EVENTS)?.publish(PluginEvent("chat.composer.insert", mapOf("text" to heard, "mode" to "append")))
```

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

### Capabilities and declared events (PLUGIN-BRIDGES-1)

A UI half answers its `runs: client` capabilities with `UiRegistrar.capability(id, ClientCapabilityHandler)`. The handler is
a suspend fun interface `call(args: JsonObject): JsonElement`; throw to fail the call. An id that module.json does not declare
with `runs: client` is ignored. `UiRegistrar.capabilityEvents` (a `CapabilityEvents`) publishes an event that module.json
`emits` declares on the daemon's event bus. The window side, `ClientCapabilityHost`, announces what the loaded modules host on
every live link and answers the daemon's calls. The handlers end up in `InfantryModule.capabilities`. Callers, targets, gates
and an example are on [Bridges](Bridges.md).

The manifest types (`ModuleManifest.kt`):

- `CapabilityDeclaration`: one `capabilities` entry: `id`, `description`, `params`, `result`, `runs`, `action`, `kind`,
  `grant`, `timeoutMs`. `actionId` is the declared action, else `capability.<id>`.
- `CapabilityRuns`: `DAEMON`, `CLIENT`, `ALL`, and `ID` (the id pattern).
- `EmitDeclaration`: one `emits` entry: `event`, `description`, `payload`.

The wire types (`CapabilityWire.kt`), shared by the daemon and every client:

- `CapabilityWire`: the frame names (`HOST`, `CALL`, `RESULT`, `EVENT`) and the encode/decode helpers.
- `CapabilityHosting`: what one client hosts (`platform`, `label`, `modules`).
- `HostedModule`: one module's hosted client capabilities and its declared events.
- `CapabilityCallFrame`, `CapabilityResultFrame`, `CapabilityEventFrame`: a call, its answer, and a client's event.

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

### Chat card renderers (CARD-2)

A reply can carry a card: a fenced block whose info string is `card:<type>` and whose body is one JSON object
(design: [CARD-REGISTRY](../CARD-REGISTRY.md)). A plugin draws a card type by registering a `ChatCardRenderer` under
the registry `chat.cards`, key = the card type:

```kotlin
registrar.implementation(CHAT_CARDS, "hello", HelloCardRenderer)
```

```json
"implementations": [{ "registry": "chat.cards", "key": "hello" }]
```

The seam is `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/ChatCards.kt`
(one commonMain type for desktop, web and Android):

| Type | What it is |
|---|---|
| `ChatCardRenderer` | `fun interface` with `@Composable Render(card: ChatCard, modifier: Modifier)`. |
| `ChatCard` | Plain data: `type` (no `card:` prefix), `id` (the `id=` attribute or null), `body` (the JSON object as text; parse it yourself), `state` (`CardState`; a renderer only sees `COMPLETE`), `updates` (empty until addressable cards ship). New fields come with defaults. |
| `CardState` | `COMPLETE`, `STREAMING`, `INVALID`. |
| `chatCardOf(block)`, `Implementations.chatCardRenderer(type)` | Helpers: the card a block carries; the renderer registered for a type. |

Rules: the registry is read when the card is drawn, so installing, enabling, disabling or removing the plugin swaps the
drawing live. With no renderer for the type (plugin off or removed), a body that is not a JSON object, or a fence still
streaming, the card shows as its original code block. A renderer that throws is dropped for the code block and listed
as its module's fault. Cards are open to every module tier (community included) and every agent role; an
app-level `ChatBlockRenderers.override("card:<type>", ...)` still wins. Example: `plugins/community/Acme_HelloCard`
(`card:hello`, body `{"title": "...", "text": "..."}`).

**Card descriptors (CARD-3).** So an agent can find and write a card without paying tokens every turn, the module also
declares each card type in module.json `cards` (read by the daemon, never by the client):

```json
"cards": [{ "type": "hello", "summary": "Greeting box: bold title and one line of text", "spec": "cards/hello.md",
            "images": { "hosts": ["*.example.com"] } }]
```

`type` is the fence's `card:<type>` (lower case, `[a-z][a-z0-9_-]*`); `summary` is the one line `infantry card list`
prints; `spec` is what `infantry card show <type>` prints: a `.md`/`.txt`/`.json` path inside the module folder, read
from there, or else the text itself (write the fields and one example fence); `images.hosts` names where the card's
images may come from (used by the card image proxy, CARD-IMG-1). Types: `CardDeclaration`, `CardImages`. The
directory is the official `Infantry_Cards` daemon half (capabilities `cards.list`, `cards.show`); the first loaded module
that declares a type owns it. A role may name a card type in its persona; with the plugin gone the role still works and
the card shows as code.

**Plugin roles (ROLE-PLUGIN-1).** A module can bring roles in module.json `roles` (read by the official `Infantry_Roles`
daemon half, never by the client):

```json
"roles": [{ "id": "hello-agent", "name": "Hello Agent", "color": "#f59e0b", "icon": "support-agent",
            "description": "Demo plugin role", "cards": ["hello"], "tools": [] }]
```

`id` follows the role-id rule; `persona` is the text itself, or a `.md`/`.txt` path inside the module folder (a missing
file is reported and the role is not seeded; a packaged install carries only `module.json`, `lib/` and `layout/`, so a
published plugin writes the persona inline);
`cards` / `tools` name preferred card types and tool or capability names, by id, from any module. When the module loads
(start, install, enable; the daemon publishes the named event `modules.changed`), the role is written to the role
catalog if its id is free, marked `plugin: <module>`. Until the owner edits it, a newer declaration refreshes it; after an
edit (`edited`) it is never rewritten. A built-in id, an owner's own role and a plugin role the owner deleted are never
taken. With the module gone the role stays and reads `disabled`; reinstalling enables it again. Preferences shape the
role's first-turn briefing (only the loaded ones are named) and never gate a tool or a card; a custom "hybrid" role sets
them in the role editor. Type: `RoleDeclaration`.

### Agent avatar renderers (AVATAR-DOCS-1)

Every agent avatar in the app is drawn by one core composable, `AgentAvatar` (`superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/AgentAvatarRenderers.kt` holds the seam; `AgentAvatar.kt` holds the composable). A plugin can add an avatar style by registering a renderer under the registry `agent.avatar.renderers`:

```kotlin
registrar.implementation(AGENT_AVATAR_RENDERERS, "mascot", MascotAvatarRenderer)  // key = the style name
```

and declaring the same pair in `module.json`:

```json
"implementations": [{ "registry": "agent.avatar.renderers", "key": "mascot" }]
```

Your renderer is any `fun interface AgentAvatarRenderer` with a `@Composable Draw(request: AvatarRequest, modifier: Modifier)`.

**The request (`AvatarRequest`).** Plain data; a renderer never sees app state or a session. Fields:

| Field | Type, default | Meaning |
|---|---|---|
| `role` | `String?`, `null` | The agent's role id: the identity, as the built-in portrait uses it. |
| `label` | `String`, `""` | Fallback text a renderer may draw as initials (an agent id when there is no role). |
| `catalogColor` | `String?`, `null` | The role's colour from the daemon catalog, when known. |
| `sizeDp` | `Int`, `28` | The size to draw in. |
| `state` | `String?`, `null` | The session's `SessionState` **name** (`STARTING`, `BUSY`, `WAITING_INPUT`, `IDLE`, `EXITED`, `ORPHANED`, `FAILED`), or `null` when the call site knows no session. It is a string on purpose: a new daemon state does not break a renderer. A renderer maps a state it does not know to idle. |
| `busyDetail` | `String?`, `null` | An extra note: `compacting` while the run compacts its context, `error` after a failed turn. |
| `motion` | `AvatarMotion`, `STATIC` | Whether this site allows the avatar to move. `STATIC` for list rows, board cards and pickers; `LIVE` only at the three focus sites (sidebar, chat header, title bar). A renderer animates only under `LIVE`. |

New fields are added with defaults, so an existing renderer keeps compiling. Removing or retyping a field breaks third-party renderers, so do not do it.

**The preference (`agentAvatarStyle`).** `AGENT_AVATAR_PREF_KEY` (`"agentAvatarStyle"`) is a per-device UI preference holding the style key. Unset means the built-in style `AGENT_AVATAR_PORTRAIT` (`"portrait"`), the role's bundled portrait PNG. The window reads it once and provides it through `LocalAgentAvatarStyle`. Note: the Appearance picker that lists the registered keys is not built yet (ticket AVATAR-PICKER-1); until it lands, a style is chosen by setting the preference itself.

**The fallback.** The built-in portrait draws in every one of these cases, so an avatar is never blank:

- the preference is unset, or names `portrait`;
- nothing is registered under that key (the plugin is not installed, disabled or removed, or the key is unknown);
- what is registered under the key is not an `AgentAvatarRenderer`;
- the renderer throws while composing. The failure is guarded (`GuardedBlock`, keyed by the owning module), the style is dropped for the portrait, and the fault is recorded against the module in Settings > Plugins (`PluginFaults`). It is never fatal.

The fallback is the portrait, not an initials disc: the initials disc is the portrait's own fallback when its PNG cannot load.

Recipe with the full minimal plugin: [Add an avatar style](recipes/add-an-avatar-style.md).

## 6. Platform services

Source: `infantryPlugins/.../plugin/api/Services.kt`. Get a service with `registrar.services.get(Services.X)`, or
`context.services.get(Services.X)` in a tab. It returns null when the module did not declare it, the owner denied
it, or the platform lacks it. A community module also needs each service under `allow` in `modules.json`.

| Key | Type | What it gives |
|---|---|---|
| `Services.NOTIFICATIONS` | `Notifications` | The core notification bus (since plugin API 2.4.0): `notify(Notification)` posts one (routed to the in-app toast and, by level, the OS), `notify(title, body)` is the short form, `dismiss(id)`, `onAction(listener)` hears your notifications' pressed actions, `present(id, channels, presenter)` makes your plugin a channel's presenter. See [Notifications](Notifications.md). |
| `Services.STORAGE` | `PluginStorage` | `read(name)`, `write(name, text)`, `list()`: your own data area, plain file names, no access to other modules. |
| `Services.SETTINGS` | `PluginSettings` | `get(key)`, `set(key, value)`: your own key/value settings. |
| `Services.EVENTS` | `EventBus` | `publish(PluginEvent)`, `subscribe(name, observer)`; the returned function unsubscribes. |
| `Services.DAEMON` | `DaemonAccess` | `suspend call("GET /health", body)`; only the routes in `module.json` `routes`. Returns `DaemonReply.Ok(status, body)`, `Refused(reason)` or `Failed(reason)`. |
| `Services.ENDPOINTS` | `EndpointAccess` | `suspend call(endpointId, method, path, body, headers)` to a backend you declared in `endpoints`; the host adds the credential. Returns `EndpointReply.Ok / Refused / Failed`. Redirects are not followed. |
| `Services.SPEECH_OUT` (`"speech.out"`, since plugin API 2.1.0) | `SpeechOutput` | `speak(text, SpeakOptions(volumePercent, voice, interrupt))`, `stop()`, `voices()`. `interrupt = false` queues the text after what is being said and returns at once; `stop()` also drops the queue. The host cuts nothing: the desktop OS voice splits text longer than one process takes into pieces played in order. A plugin that reads long text should still send it in sentence pieces (the first with `interrupt = true`, the rest `false`) so the first words come quickly and Stop works between pieces. The host speaks with the voice engine the owner enabled (VOICE-PLUGIN-1), else the OS voice. Desktop today; null on web and Android. |
| `Services.AUDIO_IN` (`"audio.in"`, since plugin API 2.1.0) | `AudioCapture` | `start(CaptureOptions(PcmFormat(16000, 1, 16), maxSeconds), onChunk, onEnd): CaptureHandle`; `CaptureHandle.stop()`, `active`. Raw PCM chunks on a host thread; `onEnd(CaptureEnd)` once, with `reason` one of `CaptureEnd.STOPPED`, `LIMIT`, `BUSY`, `UNAVAILABLE`, `FAILED`. Sensitive (see below). Desktop today (`javax.sound`); null on web and Android. |

**Notifications.** Never draw your own error toast, snackbar or native dialog (`NotificationGuardTest` fails the
build): post on the bus. A `Notification` (`plugin/api/Notification.kt`) carries `title`, `body`, `level`
(`NotificationLevel`: `INFO`, `SUCCESS`, `WARNING`, `ERROR`, `URGENT`), `id`, `source` (the host stamps your module
name), `actions` (`NotificationAction(id, label)`), `sticky`, `timeoutMs`, `dedupeKey` (repeats within
`notifications.dedupeWindowSec` are dropped: one toast per outage), `channels` (`NotificationChannels.IN_APP`, `OS`;
empty = the level's route) and optional `ticketId` / `sessionId` / `projectId`. A pressed action reaches your
`onAction` listener as a `NotificationActionEvent`. To show notifications yourself, implement `NotificationPresenter`
(`show(n): Boolean`, `dismiss(id)`) and register it with `present`; `notifications.presenter.<channel>` (a
`NotificationSettings` key) picks the one active presenter per channel, and a presenter that throws is contained and
the core default shows the notification. The bus itself is `NotificationCenter` (`plugin/host/NotificationCenter.kt`),
pure and shared by every client; its wire frames are in `NotificationWire`. Without the service (web, Android) use
`LocalShellContext.current?.toast?.notifications`, the same bus.

```kotlin
val bus = registrar.services.get(Services.NOTIFICATIONS)
bus?.notify(Notification(title = "Sync failed", body = reason, level = NotificationLevel.ERROR,
    dedupeKey = "acme.sync", actions = listOf(NotificationAction("retry", "Retry"))))
bus?.onAction { e -> if (e.actionId == "retry") sync() }
```

**Sensitive services.** `Services.SENSITIVE` (today only `audio.in`) needs the owner's grant on top of the declaration,
for every tier but core: the service id under `allow` in `modules.json`. The plugin manager asks for it at install even
for an official plugin (`infantry plugin install <ref> --grant audio.in`, or `--grant-all-requested`; later
`infantry plugin grants <id> --grant audio.in`). While a capture runs the shell draws its own listening indicator
(module name and Stop) over every block, so no plugin can record silently. One capture runs at a time per host; a
second `start` ends at once with `BUSY`. A capture lasts at most the plugin's `maxSeconds`, capped by the host setting
`audioCaptureMaxSeconds` (UI pref, else the `INFANTRY_AUDIO_CAPTURE_MAX_SECONDS` environment variable; default 60).

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

### Progress (CORE-PROGRESS-DOWNLOAD-1)

The ONE progress/loader system; full page: [Progress and downloads](Progress.md). Source:
`infantryPlugins/.../plugin/api/Progress.kt`. The same types serve the daemon (`DaemonApi.progress`) and the UI.

| Type | What it is |
|---|---|
| `ProgressTask` | One task as everyone sees it (and as the frame carries it): id, source, title, `state`, `done` / `total` / `unit`, `step` / `steps` / `stepIndex`, `etaMs`, `cancellable`, `retryable`, `error`, `link`; `fraction` (null = indeterminate), `finished`. |
| `ProgressState` | `QUEUED`, `RUNNING`, `DONE`, `FAILED`, `CANCELLED`. |
| `ProgressSpec` | What a starter asks for: title, id (blank = generated), steps, unit, total, cancellable, link, `retry` (the Retry action). |
| `ProgressHandle` | The starter's side: `step`, `amount`, `eta`, `done`, `fail(reason, retryable)`, `cancelled`, `cancelRequested`, `onCancel`. |
| `ProgressPresenter` | `show(task)`: a plugin's presentation of every change; registered with `Progress.present`. |
| `Progress` | `start`, `tasks`, `task`, `observe`, `cancel`, `retry`, `present`. |
| `NoProgress` | A host without the system: tasks run, nothing is shown. |
| `ProgressWire` | The frames `progress_task` (push), `progress_cancel`, `progress_retry` and their encoding. |
| `ProgressCenter` | `plugin.host`: the one implementation both hosts run; `mirror` stores a task pushed by another host. |

In the UI (`infantryUiCore/.../ui/progress/ProgressViews.kt`): `LocalProgress` gives the client's `ProgressUi` (the
controller mirrors the daemon's tasks into it); draw with `ProgressIndicator(taskId)`, `ProgressView(task)` or
`ProgressBar(fraction)`, never a raw Material indicator. To replace the drawing, implement `ProgressRenderer` and
register it under `PROGRESS_RENDERER_REGISTRY` (see [Implementations](#implementations)); it gets the task and its
`ProgressActions` (Cancel / Retry), runs inside `GuardedBlock`, and the core drawing (`CoreProgressView`) takes over if it
throws.

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
- `ModuleState` is `LOADED`, `DISABLED`, `LOCKED`, `REFUSED`; `ModuleStatus` carries the state, the reason and `heldOffBy` (the parents that are all off, when that is why the module is not loading).
- `PLUGIN_API_VERSION = 2`. A module whose `apiVersion` differs is refused. `PLUGIN_API_REVISION = 6` is the additive
  revision (1 added the `official` tier, 2 added sub-tabs, 3 added the `speech.out` and `audio.in` services, 4 added `capabilities` and `emits`,
  5 added `PluginScope`, 6 added the notification bus). The
  published `infantry-plugin-api` artifact is `<PLUGIN_API_VERSION>.<infantryPluginApiMinorPatch>` (gradle.properties; 2.4.0).
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

### The chat composer slot (CHAT-COMPACT-1)

The chat composer under the conversation is **one row** with a fixed footprint: input, the plugin `extras` slot, Send and
the action buttons (on a phone width the less-used ones sit in a "More actions" menu). The Activity header is a chip over
the transcript's bottom edge; the queue is one line. `PromptBox` (in `Infantry_Chat`) takes an `extras` parameter that
draws inline in that row: add a chip, an icon button or a menu item there. A plugin must not stack a new full-width row
under the chat. `MinimizeComposerUiTest` covers the composer now (CHAT-MINIMIZE-1 replaced the one-row layout and its height test): Minimize keeps the draft, and on a narrow width the last tools move into "More".

## Type index (every other public type)

Generated for the `infantryPlugins` guard `SdkTypesDocumentedGuardTest`: every public top-level type of the module is named on this page. Types above are explained in full; the rest are listed by source file with the first line of their KDoc. Read the file for the members.

### `ActionPolicy.kt`

- `ActionDeclaration`: POLICY-1: one entry of module.json `actions`: an action a plugin's controls perform.
- `ActionKind`: POLICY-1 (docs/POLICY.md): what a named action does.
- `ActionPolicyMatcher`: POLICY-1: the ONE matcher.
- `FrameAction`: GAP-AUDIT-1 (h): one catalog entry of [PolicyWire.frames]: the action a frame performs and its kind.
- `PolicyChoice`: POLICY-1: a preset a client may pick in Settings.
- `PolicyPreset`: POLICY-1: one preset file (`policies/<id>.json`).
- `PolicyRefusal`: GAP-AUDIT-1 (h): the ONE refusal text, the daemon's and the client's send gate's (a preset's `refusal` template).
- `PolicyWire`: POLICY-1: the `policy` frame (daemon -> client, after `ready` and on every change).
- `SeatsSpec`: POLICY-1: a preset's seated lockdown: a project, its role seats and the visitor limits (docs/POLICY.md section 2).

### `AgentDefinition.kt`

- `AgentDefinition`: AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3): one agent a module contributes, as data.
- `AgentDefinitions`: AGENT-REGISTRY-1: the rules every contributed [AgentDefinition] passes, the same on every platform.
- `AgentSchedule`: `agents[].schedule`: every [everyMinutes] (at least 1), the agent is given [prompt].
- `AgentUiHints`: `agents[].ui`: a `#rrggbb` [color] and a client icon key [icon] (`[a-z0-9-]`).

### `DependencyGraph.kt`

- `DependencyGraph`: PLUGINS-2: the dependency rules, pure, shared by the loader ([ModuleResolver]) and by Settings when it changes modules.json: - a dependency must be installed, enabled and inside its version range; - a
- `ModuleChange`: PLUGINS-2: the outcome of asking to enable, disable or remove a module.
- `ModuleNode`: PLUGINS-2: one installed module as the dependency rules see it.

### `EndpointGate.kt`

- `EndpointGate`: PLUGIN-ENDPOINTS-1 (docs/PLUGIN-ENDPOINTS-DESIGN.md D1, D5): the pure rule every platform shares for a module's backend call.

### `GrantPattern.kt`

- `GrantPattern`: GAP-AUDIT-1 (e): how a modules.json grant (`allowOverride`, `allowInject`) matches a node id or selector.

### `IhqInstaller.kt`

- `IhqInstaller`: S5: installs a `.ihq` (a zip with `module.json`, the jars under `lib/` <!-- docs-freshness: ignore --> and a jarsigner signature over the whole zip) into the tier folder that [PluginFolders.scan] already reads.
- `IhqRefusal`
- `IhqResult`
- `InstallSettings`: S5 (desktop JVM only): the settings of an install, `<plugins root>/install.json`.
- `NestedDep`: NS3: one `deps/<name>.ihq` of a composition: its own [tier] from its own [signer].

### `ModuleManifest.kt`

- `BlockDeclaration`: A block named [name] the module adds to slot [slot].
- `CardDeclaration`: CARD-3: one chat card type the module brings: `type`, `summary`, `spec` (a file in the module folder or the text), `images`.
- `CardImages`: CARD-3: module.json `cards[].images`: the image hosts a card type declares.
- `RoleDeclaration`: ROLE-PLUGIN-1: one role the module brings (module.json `roles`): `id`, `name`, `persona` (a file in the module folder or the text), `color`, `icon`, `description`, preferred `cards` and `tools`.
- `BlockTypeDeclaration`: NS1 / PB1 (6t.2): a block type, `vendor.name` (core's layout primitives are `layout.row` ...).
- `DaemonCapabilities`: NS3 (1a): `daemon.needs` (refused = the module does not work) and `daemon.wants` (refused = it degrades).
- `DaemonFrameDeclaration`: DAEMON-PLUGINS-1: one plugin-defined frame; [ownerOnly] frames are refused to a community module.
- `DaemonPluginLimits`: DAEMON-PLUGINS-1: a daemon half's limits; null = the host default.
- `DaemonStorageDeclaration`: DAEMON-STORAGE-1: [adopt] = core tables an official module takes over (a community module declaring any is refused).
- `DaemonToolDeclaration`: DAEMON-PLUGINS-1: one daemon tool: its name, its policy action id and kind, and a one-line description.
- `DependencyDeclaration`: PLUGINS-2: module [id] (`Vendor_Module`) must be installed, enabled and at a version in [version] (e.g.
- `EndpointAuth`: [kind] is `none / bearer / header / basic`; [name] is the header name for `header`; [field] the keystore field (D3, later).
- `EndpointDeclaration`: PLUGIN-ENDPOINTS-1 (D1): one backend the module calls by [id], never by URL.
- `FillDeclaration`: NS3: a fill of shell slot [node]; [layout] (a `layout/<name>.json` path in the module folder) when the tree is data.
- `FillDeclarationSerializer`: NS3: a fill entry is a plain node id string or the object form.
- `HarnessAuthProbeDeclaration`: CLAUDE-AUTH-CHOICE-2: `harness.authProbe`, the argv that reports a subscription login as JSON, the field that says signed in, and the login command shown to the owner.
- `HarnessCredentialDeclaration`: FIRST-RUN-KEYS-1: `harness.credential` / `harness.credentials`, the env variable a harness reads a key from (the key lives in the keystore), its label, help, id and provider; RUNNER-CRED-1: `forMode` names the auth mode it serves (a `subscription-login` token goes only in that mode and is never asked for). HARNESS-ALL-1: `modelPrefix` (e.g. "deepseek/" for the DeepSeek models) ties a key to one provider's models: a launch warns that it is missing only when its model starts with the prefix; the key is still lent to every session.
- `HarnessCliDeclaration`: HARNESS-PLUGIN-3 H4: `harness.cli`, the CLI the harness drives; [install] is how the harness installer gets it.
- `HarnessCliInstall`: `harness.cli.install`: an npm package and the bin script inside it.
- `HarnessDeclaration`: HARNESS-PLUGIN-3 H4: module.json `harness`.
- `ImplementationDeclaration`: P1 step 2: the module registers an implementation under [key] of registry [registry] (e.g.
- `InjectDeclaration`: BLOCKS-INJECTORS J2 (docs/BLOCKS-INJECTORS.md section 2a): the module's code [name] (registered with `UiRegistrar.inject`) is drawn as a child `<module>.<name>` of node [into] (a node id, or its addre
- `InterceptorDeclaration`: A hook named [name] on contribution [target]; [kind] is before, after or around.
- `NodeDeclaration`: GAP-AUDIT-1 (b): a node the module draws; [overridable] false = locked against every other module.
- `ObserverDeclaration`: An observer named [name] of event [event].
- `PanelDeclaration`: PLUGINS-3: a section the module adds to side panel [panel] (`left` or `right`).
- `PreferenceDeclaration`: The module replaces contribution [replace] (by id) with its own contribution [with].
- `ShellOverrideDeclaration`: BLOCKS-LAYOUT L5: the module's code [name] (registered with `UiRegistrar.shell`) takes over core shell node [node] (a node id such as `infantry.shell.footer`, or its address `infantry.shell#infantry.s
- `TabDeclaration`: A tab the module contributes (the first contribution type; a panel or a whole screen come the same way later).

### `ModuleResolver.kt`

- `AcceptedPreference`: A preference the rules let through: [module] replaces [replace] with its own [with].
- `AcceptedShellOverride`: BLOCKS-LAYOUT L5: an override of core shell node [node] the trust rules let through ([mode] `replace` or `wrap`).
- `ModuleCandidate`: A module found in a plugin folder, or compiled into the app ([dir] null).
- `Resolution`: The outcome of one load: every module's status (in load order for the loaded ones, then the rest), the preferences and shell overrides that apply (in load order), and the log lines (one per module, pl

### `ModulesConfig.kt`

- `DemoLockConfig`: What the demo lock lets a module's daemon access through, on top of the routes the module declared.
- `EndpointSetting`
- `ModuleSetting`: The owner's say over one module.
- `PluginFolder`: One plugin subfolder of the plugins root and the tier of everything in it.

### `NodeConfig.kt`

- `ConfigChangedFrame`: CONFIG-8: `config_changed`, pushed to every connected client after a write; [by] is who wrote it.
- `ConfigClassSlot`: CONFIG-9: one slot of a `classes` field.
- `ConfigDeclaration`: P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md section 6s A): the config fields a module declares for node [node] (an `infantry.shell.*` id it owns or is granted), shown in the configuration view under tab [
- `ConfigFieldTypes`: The built-in field types (6s A).
- `ConfigFieldValidator`: CONFIG-8: the check a declared field applies to a written value; the daemon and a client share it.
- `ConfigManifest`: CONFIG-3 (docs/CONFIG-MANIFEST-DESIGN.md section 2): one host's compiled unified manifest, as `GET/PUT /config-manifest` carry it.
- `ConfigManifestSource`: One contributor to a [ConfigManifest]: a module, its version and the hash of its own `config`.
- `ConfigSchemaFrame`: CONFIG-8 (docs/CONFIG-MANIFEST-DESIGN.md section 6): `config_schema`, the daemon's own `scope:"daemon"` declarations; [rev] moves on every schema or value change.
- `ConfigScopes`: CONFIG-4: the override scopes a field may declare.
- `ConfigSetBy`: Who wrote a stored value: the merged JSON tree on first load, or the user from the configuration view.
- `ConfigSetFrame`: CONFIG-8: `config_set` (client to daemon).
- `ConfigValuesFrame`: CONFIG-8: `config_values`, the stored value of every declared daemon key (a key never set is absent: the client resolves the default).
- `ConfigVisibleWhen`: CONFIG-FIELDS-2: show a field only while the sibling field [key] holds [eq] (compared as text; `true`/`false` for a checkbox).
- `NodeConfigEntry`: The wire form of one `node_config` row (6s B): `GET /node-config` answers a list of them, `POST /node-config/seed` takes one (only missing keys are inserted), `PUT /node-config` writes one ([value] nu

### `Notification.kt`

- `Notification`: NOTIFY-CORE-1: one notification on the core bus (title, body, level, actions, sticky/timeout, dedupe key, links).
- `NotificationAction`: one button of a notification (id + label).
- `NotificationActionEvent`: a pressed action: the notification, the action and the module that posted it.
- `NotificationChannels`: the channel ids (`inApp`, `os`) and `core`, the core default presenter's id.
- `NotificationLevel`: info, success, warning, error, urgent (the wire names).
- `NotificationPresenter`: shows a channel's notifications (`show`, `dismiss`); registered with `Notifications.present`.
- `NotificationSettings`: the `notifications.*` settings: routes per level, presenter per channel, quiet hours, dedupe window, history size.
- `NotificationWire`: the frames (`notification_push`, `notification_action`, `notification_dismiss`) and their codec.

### `NotificationCenter.kt`

- `NotificationCenter`: NOTIFY-CORE-1: the bus of one host: dedupe, routing, one active presenter per channel, fault containment, history.

### `OfficialTrust.kt`

- `OfficialTrust`: RUNTIME-2 (EMPTY-SHELL-DESIGN 3b): an official module loads only when its jar proves it was made by Infantry.

### `PluginJarShadow.kt`

- `PluginJarShadow`: PLUGIN-UPDATE-LOCK-1: the per-process copy of a plugin's jars (and, on demand, its native libraries) that its class loader reads, so nothing holds the install folder and the manager can update, roll back or remove a running plugin on Windows; deleted when the loader closes, a dead process's copies by the next process ([Plugin lifecycle](Plugin-lifecycle.md)).
- `ShadowJarClassLoader`: the `URLClassLoader` over a `PluginJarShadow`: every plugin half (UI `PluginClassLoader`, daemon half, harness jar) loads through one; `close` also deletes the copy.

### `PluginFolders.kt`

- `FilePluginSettings`: A module's settings, one JSON object in its data area.
- `FilePluginStorage`: A module's private data area: files directly in [dir] (names cannot climb out of it).
- `ModulesFile`: PLUGINS-2: writes the owner's enable list back to `<root>/modules.json` after Settings changed it.
- `PluginFolders`: PLUGINS-1 (JVM): where the plugins live and what is in them.

### `Progress.kt`

- `ProgressTask`, `ProgressState`, `ProgressSpec`, `ProgressHandle`, `ProgressPresenter`, `Progress`, `NoProgress`, `ProgressWire`: see [Progress](#progress-core-progress-download-1).

### `ProgressCenter.kt`

- `ProgressCenter`: CORE-PROGRESS-DOWNLOAD-1: the one implementation of `Progress`, run by the daemon and by each client.

### `PluginManagerPort.kt`

- `ActionResult`: What a change did.
- `HitView`
- `InspectOutcome`: Exactly one of [view] and [error] is set.
- `InspectView`: What the consent dialog shows.
- `PluginManagerPort`: PLUGIN-UI-1 (docs/PLUGIN-MANAGER.md section 4.3): the plugin manager as a UI plugin sees it.
- `PluginView`: One installed or bundled plugin. `enabled` is the OWN flag; PLUGIN-TREE-2b adds `parents`, `children` (direct, from `PluginTree`) and `heldOffBy` (the parents that are all off, when that holds the plugin off). `PluginManagerPort.openConfig(id)` is the step-in hook (default no-op; 2c wires it).
- `RefreshView`
- `PluginProgress`: PLUGIN-PROGRESS-1: one live step (Downloading / Verifying / Installing / Reloading, bytes when known) a host reports through `PluginManagerPort.setProgressListener`.
- `SourceView`
- `UpdateView`: A newer version offered for an installed plugin; [newGrants] non-empty = "asks for new permissions".

### `PluginTree.kt`

- `PluginTree`: PLUGIN-TREE-UI-1: the parent/child relation between modules, from manifest data only. PLUGIN-TREE-2a: edges also come from `inject` targets; a cycle's closing edge is dropped and listed in `problems`.

### `SemVer.kt`

- `SemVer`: PLUGINS-2: a semantic version, `MAJOR.MINOR.PATCH[-pre][+build]`.
- `VersionRange`: PLUGINS-2: a version range, the npm subset a module.json needs, parsed without a library: - `*`, `x` or empty: any version; - comparators `>=1.2`, `>1.2`, `<=2`, `<2`, `=1.2.3`, joined by spaces (all 

### `ServiceRegistry.kt`

- `ConfigReadGate`: CONFIG-FIELDS-3 (G8): who may read another node's config.
- `GatedEventBus`: One module's view of the bus: it may publish and observe only the events it declared.
- `RouteGate`: The daemon rule: a route must be declared, and under the lockdown also allowed by the lock.
- `ServiceGate`: The service rules: declared in module.json, not denied by the owner, and for a community module, allowed.
- `ServiceRegistry`: PLUGINS-1: the host's service registry, the extension point for platform services.
- `SimpleEventBus`: A plain in-process event bus. PLUGIN-HOTSWAP-1: it records each observer's owner module (`dropOwner` removes one module's observers) and binds one observer object once.
- `LeakWatch`: PLUGIN-HOTSWAP-1: asks the GC to collect a swapped-out plugin's class loader and says whether it did within a timeout; `heapDump` for the dev leak report ([Plugin lifecycle](Plugin-lifecycle.md)).

### `PluginScope.kt`

- `PluginScope`: PLUGIN-HOTSWAP-1 (plugin API revision 5): a plugin half's lifetime, owned by the host: `coroutines`, `own`, `onUnload`, `active`, and the `every` timer helper. Ends by itself on unload ([Plugin lifecycle](Plugin-lifecycle.md)).
- `OwnedPluginScope`: the host's side of a `PluginScope`: one per loaded plugin half; the host calls `close` on unload (cancel, then cleanups newest first; never throws).

### `Services.kt`

- `ServiceKey`: PLUGINS-1: platform services.

### `UpdateDeclaration.kt`

- `UpdateCredentialRef`: The NAMES under which a source's secret is looked up, in this order: the environment variable [env], then the daemon keystore entry [secret].
- `UpdateDeclaration`: UPDATE-ENGINE-1 (docs/wiki/Updates.md): a component's update declaration, the `update` object of a module.json (and the `update.source.*` settings for the app itself).
- `UpdateSourceRef`: Where one component's new versions live.
