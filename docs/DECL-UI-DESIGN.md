# DECL-UI: declarative plugin views for every client (design only)

Ticket DECL-UI-0 (b4681a4a), 2026-11-03. Input: `docs/PLUGIN-REPO-DESIGN.md` section 5 (PLUGIN-REPO-1, 9710c637), which
picked (c) daemon-only + (b) curated compile-in now and (a) declarative UI as the next build. This file designs (a). No code.

Constraints (not reopened here): Android and wasm cannot load code (D6, D9; Google Play forbids downloaded executable code);
a plugin's UI on those clients must be **data** drawn by widgets compiled into every client; core holds only the generic
mechanism and names no plugin (AGENTS.md rules 1-2).

## 0. The idea in one paragraph

A plugin's **daemon half** produces a *view*: a page in the existing 6t.5 page format (`blockType`, `id`, `config`,
`children`; `PageJson` / `PageLayout.instantiate` in `infantryUiCore/.../ui/block/layout/`), whose leaves are a fixed
**widget vocabulary** (`ui.*` block types) and whose containers are the core layout primitives (`layout.column`, `layout.row`,
...). The client fetches the view over one new frame family (`plugin_view*`), builds it through the block-type registry and
draws it. A button is a **daemon tool call** (the existing `plugin_tool_call`, gated by the tool's own policy action on the
caller's connection). When the plugin's state changes, the daemon half says "view changed" and every client that has it
open re-fetches. Nothing executable crosses the wire, so the same view runs on desktop, web and Android, and the plugin is
installed **only on the daemon**: the phone never downloads the plugin at all.

```
 daemon half (any tier)                 daemon core (generic)                    client (desktop / web / Android)
 api.views.provide("main") { req -> }   plugin_views      -> list of views        Infantry_Views (official plugin, compiled in)
 api.views.changed("main")      ----->  plugin_view       -> page JSON (checked)   ViewAccess service (core) -> frames
 api.tools.register(mark)               plugin_view_changed -> subscribed conns    view host block -> PageLayout.instantiate
                                        plugin_tool_call (+ origin) -> tool        ui.* widgets draw; button -> act()
```

## 1. Widget vocabulary v1

### 1.1 Rules every node follows

A node is the 6t.5 node, unchanged: `{"blockType": "<type>", "id": "<id unique in this page>", "config": {...},
"children": [...]}`. Widget fields go in `config` (exactly where a composition stores a block instance's fields), so a
`ui.*` widget is an ordinary block type: it also works inside a desktop composition configured through `node_config`.
Two new optional node keys, both reserved for every type:

| key | meaning |
|---|---|
| `fallback` | text a client draws in place of this node when it does not know `blockType` (section 1.4) |
| `show` | `Always` / `Wide` / `Compact`, the existing `Show` placement (Contract.kt), so one view adapts to a phone |

Containers are the core primitives that already exist as block types (`CoreBlockTypes`): `layout.column`, `layout.row`,
`layout.stack`, `layout.spacer`, `layout.group`, with their `weight`, `size`, `show` fields. No new layout widget is added.

Text is plain text everywhere: no markdown, no HTML, no colour strings. Visual choice is a closed **tone** enum
(`default`, `muted`, `good`, `warn`, `bad`) mapped by the client to its own theme, the same rule as config `classes`
(token allow-lists, no CSS from data; CONFIG-FIELDS section 4.4).

### 1.2 The set

| type | fields (`config`) | children | why it is in v1 |
|---|---|---|---|
| `ui.section` | `title?`, `subtitle?`, `collapsible` (false) | yes | the card every screen is made of; gives the view structure |
| `ui.text` | `text`, `style`: `body` \| `title` \| `caption` \| `mono` (body), `tone` (default), `maxLines?` | no | the minimum |
| `ui.badge` | `text`, `tone` | no | status at a glance (board, plugins, health) |
| `ui.stat` | `label`, `value` (string), `tone?`, `hint?` | no | a metric tile; covers the dashboard need without a chart |
| `ui.list` | `items`: `[{id, title, subtitle?, badge?: {text, tone}, action?}]`, `empty?` (text when no items) | no | the most common plugin screen (feeds, queues, results) |
| `ui.table` | `columns`: `[{key, label, align?: start\|end}]`, `rows`: `[{id, cells: {key: string\|number}, action?}]`, `empty?` | no | tabular data; Compact width shows the first two columns + a row tap |
| `ui.button` | `label`, `action`, `style`: `primary` \| `secondary` \| `danger` (secondary), `confirm?` (text of a confirm dialog), `enabled` (true) | no | the only way to act |
| `ui.form` | `fields`: `ConfigFieldDeclaration[]`, `values`: `{key: value}`, `submit`: `{label, action}` | no | input; reuses the config field types (section 1.3) |

`action` (on a button, a list item, a table row, a form submit) is exactly one of:

```json
{"tool": "acme_hello_mark", "args": {"id": "42"}, "refresh": true}
{"open": {"view": "detail", "params": {"id": "42"}}}
```

- `tool`: a daemon tool **of the view's own module** (checked by the daemon, section 2.4); `args` are sent as given, a form
  adds its values under their field keys (a form value wins over a same-named `args` key). `refresh` (default true) re-fetches
  the view after the call. The tool's result text is shown as a toast when the tool says `isError`, or when the view sets
  `"toast": true` on the action.
- `open`: another view of the same module with `params` (list item -> detail screen). The client keeps a back stack per tab.

### 1.3 `ui.form` reuses the config field types

`fields` is a list of `ConfigFieldDeclaration` (infantryPlugins `NodeConfig.kt`), not a new schema. Supported in v1:
`text`, `textarea`, `number`, `checkbox`/`toggle`, `select`, `radio`, `multiSelect`, with `label`, `description`, `default`,
`options`, `optionLabels`, `min/max/step`, `required`, `pattern`, `patternMessage`, `minLength`, `maxLength`, `visibleWhen`,
`placeholder` (all built, CONFIG-FIELDS G1/G2/G5). The editors are the ones Settings uses (`NodeFields.kt`) and validation is
`ConfigFieldValidator` before send; the tool's `inputSchema` validates again on the daemon (the client is never trusted).
Not in v1 (render read-only, the existing unknown-type rule): `color`, `classes`, `list`, `group`, `implementation`, custom
module field types (they are code, so a mobile client cannot have a community one), `secret` (G4, not built; a secret
never travels in a view).

### 1.4 Left out on purpose

| left out | why |
|---|---|
| `ui.chart` | the chart style is still moving (`docs/CHART-STYLE.md`); `ui.stat` + `ui.table` cover v1. First candidate for level 2 |
| images / icons by URL | a fetch from data is a tracking and content-policy surface; icons only from the client's own set later (`icon` key, level 2) |
| markdown / rich text | a parser is a second contract; plain text first |
| free-form styling (colours, sizes, fonts, CSS) | breaks theming and accessibility; tones only |
| expressions, data binding, templates (`${...}`, `repeat`) | the daemon renders the whole page (section 2.1); an expression language is the most expensive contract of all |
| client-side state beyond form input and the back stack | state lives in the daemon half; a client restart loses nothing |
| WebView / HTML widget | section 5 option O3; no desktop WebView, bearer-token and XSS surface |
| text input outside a form, live search | a form with a submit covers it; keystroke round-trips cost tokens of nothing but latency |
| node addressing by other plugins (`shell`, `inject` into a view) | view node ids are private to the view in v1 (section 2.2) |

### 1.5 Versioning: the public-contract rule

- **Vocabulary level**, one integer, v1 = `1`. The client knows its level (`Infantry_Views` constant). A page says which
  level it was written for: `{"schema": 1, "widgets": 1, "root": {...}}` (`schema` is the existing PageJson schema).
- Inside a level line, changes are **additive only**: a new widget type, a new optional field, a new enum value. Each
  raises the level by one. Never: renaming or removing a widget or field, changing a field's type or meaning, making an
  optional field required. A breaking change is a **new widget name** (`ui.table2`); the old one is kept and drawn forever.
- Unknown field: ignored. Unknown enum value: the field's default. Unknown `blockType`: the node's `fallback` text in muted
  style, else one line `"<type> needs a newer app"`; its children are not drawn (they could depend on the parent's meaning).
  A page whose `widgets` is above the client's level is still drawn (nodes degrade one by one) under a one-line banner
  "made for a newer version of the app". Nothing throws; every problem is one line, as `PageLayout.instantiate` does today.
- The daemon does **not** validate widget names (core must not know them); it checks only shape and limits (section 2.5).
  The vocabulary as data (`widgets.schema.json`, owned by `Infantry_Views`) is what the authoring lint and the example test use.

## 2. Data and actions

### 2.1 Server-driven pages (the choice)

The daemon half returns the **whole materialised page** for `(view, params)`: data is already in the widget fields (a list's
`items`, a table's `rows`). There is no binding step on the client. This keeps the client a dumb, safe renderer, the plugin's
logic in one place (its daemon half), and the contract to node shapes only. Cost: a refresh re-sends the page, bounded by the
limits in 2.5. Patches are an additive later step (section 5, deferred), not v1.

### 2.2 Wire (contract, additive; `infantry/CONTRACT.md` minor bump)

| frame | data | answer | tier / action |
|---|---|---|---|
| `plugin_views` | `{}` | `plugin_views_result {views: [{module, id, title, place, platforms?, widgets}]}` | read; lists views of LOADED modules whose view action the caller passes |
| `plugin_view` | `{module, view, params: {}, subscribe: true}` | `plugin_view_result {module, view, params, rev, page}` or `{..., error}` | the view's declared `action` (read) |
| `plugin_view_close` | `{module, view, params}` | none | same action; also implied on disconnect |
| `plugin_view_changed` | push: `{module, view, params?, rev}` | client re-sends `plugin_view` | only to connections subscribed to that view |
| `plugin_tool_call` | existing `{name, arguments}` + new optional `origin: {module, view}` | existing `plugin_tool_result` | the tool's own action (unchanged) |

- `rev` is the daemon's counter per `(module, view, params)`; a client drops an answer older than what it shows.
- `params` is a flat JSON object of strings/numbers (the `open` action's `params`); the subscription key is
  `(connection, module, view, canonical params)`. `plugin_view_changed` without `params` means every params of that view.
- Node ids inside a page are the plugin's own; the client prefixes them `view:<module>/<view>/` when it builds blocks, so
  they never collide with shell node ids and are not addressable by overrides or injections.

### 2.3 Daemon API (`infantryDaemonApi`, additive version)

```kotlin
interface Views {
    /** Answers `plugin_view` for [view] (declared in `daemon.views`); runs under the plugin's call limits. */
    fun provide(view: String, provider: (ViewRequest) -> ViewReply)
    /** Tells subscribed clients that [view] (one [params], or all when null) changed; coalesced by the host. */
    fun changed(view: String, params: JsonObject? = null)
}
data class ViewRequest(val view: String, val params: JsonObject, val caller: Caller)
sealed interface ViewReply {
    data class Page(val page: JsonObject) : ViewReply
    data class Refused(val reason: String) : ViewReply
}
```

`DaemonApi.views` defaults to a no-op (older daemons answer `plugin_view refused: this daemon has no views`). The API names
no widget: a plugin author emits `JsonObject`s (kotlinx.serialization is on the plugin class loader already). Typed builders
for the `ui.*` widgets are a copyable source file in the example (section 3.3), not SDK surface, so the SDK guard
(`SdkSurfaceGuardTest`) is untouched and the vocabulary stays owned by `Infantry_Views`.

`changed` is allowed for **every tier**, unlike `events.broadcast` (official/core only): it pushes nothing but an id to
connections that asked for that very view, and the host coalesces it (setting below), so a community plugin cannot spam.

### 2.4 Auth

- Reading a view: each view declares a policy `action` (kind `read`), merged into `ActionCatalog` like a tool's; the active
  preset can refuse it. A community module needs the grant `daemon.view` in its modules.json `allow` (as `daemon.tool`).
- Acting: a button is a `plugin_tool_call`, so the tool's own action id, the caller's connection, FrameGate, the policy and
  the frame-gate hooks all apply unchanged; a tool's `ToolCall.request` frames pass every gate the caller's frames pass.
  A view never gets more than the client that shows it.
- `origin`: when present, the daemon refuses a tool that is not registered by `origin.module` ("a view may call only its own
  module's tools"). This stops a view from dressing up a button that calls another plugin's or core's destructive tool.
- Destructive tools (action kind `destructive`) always get a confirm dialog on the client, whatever the view's `confirm`.
- Demo lock: community modules are already LOCKED there, so their views are not listed; core/official views follow the
  lock's frame allow-list like any frame.

### 2.5 Limits (named settings, cheapest default; `LimitsGuardTest`)

| setting | default | what happens past it |
|---|---|---|
| `limits.views.maxPageBytes` | 262144 | refused: `plugin_view_result.error` = one line |
| `limits.views.maxNodes` | 500 | refused |
| `limits.views.maxDepth` | 16 | refused |
| `limits.views.changedCoalesceMs` | 1000 | `changed` calls inside the window collapse into one push |
| `limits.views.maxSubscriptionsPerConnection` | 16 | the oldest subscription is dropped |

The provider runs under the module's existing `callTimeoutMs` / `cpuMsPerCall`; a breach disables the plugin as for a tool.

## 3. How a view ships

### 3.1 Declared in module.json, under `daemon`

```json
"daemon": {
  "entry": "com.acme.hello.daemon.HelloDaemon",
  "views": [
    { "id": "main",   "title": "Hello", "place": "tab", "action": "acme.hello.read" },
    { "id": "detail", "title": "Item",  "place": "none", "action": "acme.hello.read" },
    { "id": "about",  "title": "About", "place": "tab", "layout": "layout/about.json", "platforms": ["android", "web"] }
  ],
  "tools": [ { "name": "acme_hello_mark", "action": "acme.hello.write", "kind": "write", "description": "..." } ]
}
```

- `place`: `tab` (listed as a screen) or `none` (reached only by `open`). Other placements are later (section 5).
- `layout`: a static page file in the module folder. The daemon serves it when no provider is registered for the id, so a
  **code-free module** (no `entry`) can ship an informational view; a registered provider wins over the file.
- `platforms`: the existing PLUGIN-PLATFORMS-1 vocabulary. A plugin that also has a Compose tab on desktop sets
  `["android", "web"]` so desktop does not show it twice. Absent = all clients.
- Undeclared view ids are refused at `provide` (logged), as every other daemon registration is.

### 3.2 `.ihq` and runtime installs

The `.ihq` package already carries `module.json`, `lib/*.jar` and (composition packages) `layout/*.json`. A declarative
plugin is a normal `.ihq` with a daemon jar and optional `layout/*.json`; it installs **on the daemon host** through the
existing installer (`IhqInstaller`, PLUGIN-UI-1 / PLUGIN-HOTLOAD-1 install, update and reload it live). Android and web
clients never receive the package: they ask the daemon `plugin_views` and get JSON. That is the store-safe part: the client
binary is unchanged, no code is downloaded, the widgets are the ones compiled in. Hot-loading a module re-lists its views
(the daemon pushes a `plugin_views` refresh notice the same way, `plugin_view_changed` with `view: "*"` for the module).

A code-free **composition** package (NS3, `fills` with `layout`) stays the desktop slot mechanism: it fills shell slots with
any registered block types, Compose ones included. A composition whose page uses only `layout.*` and `ui.*` types could be
served as a view too; that unification is deferred (it needs `fills` semantics on mobile, which has a different shell).

### 3.3 Authoring

`plugins/examples/Acme_HelloView` (slice DV-6): a daemon half with one `tab` view, one `none` detail view, one tool, a
`changed` call after the tool mutates state, a static `about` layout, and a `Widgets.kt` builder file to copy
(`text("Hi")`, `list(items)`, ...). `docs/wiki/declarative-views.md` explains it; a test lints the example's pages against
`widgets.schema.json`.

## 4. Desktop renders the same views

- **One renderer everywhere**: the official plugin `Infantry_Views` (UI half, `platforms` all) holds the `ui.*` block types
  (declared in its `blockTypes`), the view host block and the "Views" tab. Desktop loads it like any official plugin; web and
  Android compile it in through the generated module list (curated compile-in, option (b), so D6/D9 hold).
- v1 placement: one "Views" tab with one sub-tab per listed view (`SubTabs` exists). A first-class tab per view needs a seam
  core lacks today (tabs are registered once in `UiPlugin.register`, `TabProvider` is static): a **dynamic tab source**
  (an observable list of `TabProvider`s a plugin may register). That is a generic seam and slice DV-8, after v1 works.
- Compose jars stay the full-power desktop path (custom drawing, drag and drop, local files, terminals). A plugin can ship
  both; `platforms` on the view keeps desktop from showing it twice.
- Props reach a widget through `scope.config()`: the view host wraps the `ShellContext` config for its subtree so an
  instance id's values are the node's inline `config` first, then the type's declared defaults. A widget therefore reads its
  fields the same way in a view and in a configured composition. If wrapping `ShellContext` turns out not to fit, the
  fallback is a `LocalViewProps` composition local read by the widgets (decided inside DV-3, invisible to plugins).

## 5. Options compared, trade-offs, what is expensive to reverse

### 5.1 Rendering model

| option | what | for | against | verdict |
|---|---|---|---|---|
| **O1 server-driven pages** | daemon returns the materialised page; actions are tools | smallest client and contract; logic in one place; reuses PageJson, block types, tools, policy | a refresh re-sends the page; no offline | **chosen** |
| O2 templates + bindings | static layout with `${data.x}` / `repeat`; daemon returns data | smaller refreshes; layouts cacheable; code-free plugins richer | an expression language is a second, huge public contract; two places for logic | later, as an additive `source` key if O1's payloads hurt |
| O3 WebView / HTML | plugin serves HTML on its `/plugins/<id>/` route | full power for authors; Play allows JS in a WebView | no desktop WebView (JCEF is heavy); bearer and XSS surface; no per-action policy; looks foreign | rejected for v1 |
| O4 client-side scripting (Lua on the client) | ship scripts, interpret on the device | logic offline | an interpreter on every client; scripting is a daemon plugin (D17/D19); review burden | rejected |
| O5 stay at (c)+(b) | no custom screens for community plugins on mobile/web | zero cost | the gap PLUGIN-REPO-1 named stays | today's state, replaced by O1 |

### 5.2 Where the vocabulary lives

| option | for | against | verdict |
|---|---|---|---|
| **official plugin `Infantry_Views`** | rule 1 (a widget set is a feature); replaceable; core stays a frame + a service | the public contract is versioned with a plugin | **chosen**; the level integer (1.5) is the contract, not the plugin version |
| core (`infantryUiCore`, beside `layout.*`) | one less module | grows core with UI features; guards would have to whitelist widget names | rejected |

### 5.3 Actions

| option | verdict |
|---|---|
| **reuse `plugin_tool_call` (+ `origin`)** | **chosen**: tools already have names, schemas, policy actions, MCP exposure (an agent can press the same "button") |
| a new `plugin_view_action` frame with view-local handlers | rejected: a second gated action surface beside tools, invisible to agents |

### 5.4 Expensive to reverse (say plainly)

1. **Widget names and fields** (1.2). Every declarative plugin and every installed client depends on them; a client in a
   store cannot be updated on our schedule. Hence additive-only and new-name-for-breaking (1.5).
2. **The node shape** (`blockType`, `id`, `config`, `children`, + `fallback`, `show`). Already public through compositions;
   this design deliberately does not add a second one.
3. **Actions are daemon tools of the view's own module.** Widening later (calling core tools from a view) is additive;
   narrowing after plugins rely on it would break them, so v1 starts narrow.
4. **Server-driven pages.** Adding bindings later is additive; removing them would not be, so v1 has none.
5. **Frame names and fields** (`plugin_views`, `plugin_view`, `plugin_view_close`, `plugin_view_changed`, `origin`): wire contract.
6. **Plugins install on the daemon, clients get data.** This is the store-safety argument; a design that ships view packages
   to phones would reopen the Play policy question.

Cheap to change later: limits and their defaults, coalescing, placement beyond `tab`, the "Views" tab vs per-view tabs,
patches instead of whole pages, the widget builder helpers.

## 6. Slice plan (build tickets, Sonnet, scoped tests)

Order is a chain except where noted; each ticket names only its own test classes (the full suite runs on staging).

| id | slice | depends on | weight |
|---|---|---|---|
| DV-1a | daemon: `daemon.views` declaration, `DaemonApi.views.provide`, `plugin_views` + `plugin_view` request/answer, static `layout` serving, view policy actions + `daemon.view` grant, limits settings, CONTRACT.md | - | 5 |
| DV-1b | daemon: subscriptions, `views.changed` coalesced push, `plugin_view_close`, disconnect cleanup, `origin` check on `plugin_tool_call`, re-list on hot-load | DV-1a | 4 |
| DV-2 | client core: `Services.VIEWS` (`ViewAccess`: `list`, `open` -> Flow of states, `act`) over the frames in `Controller`; stub daemon support | DV-1a (DV-1b for push) | 4 |
| DV-3 | `Infantry_Views` plugin: module.json, view host block (fetch, instantiate, id prefix, props, unknown widget, error card, newer-app banner), "Views" tab with sub-tabs, `ui.section` `ui.text` `ui.badge` `ui.stat` | DV-2 | 4 |
| DV-4 | widgets `ui.list` `ui.table` `ui.button` + actions (`tool` via `act`, `open` + back stack, confirm, refresh, toast) | DV-3 | 3 |
| DV-5 | `ui.form` on the config field editors + validator, submit action | DV-4 | 3 |
| DV-6 | `plugins/examples/Acme_HelloView`, `widgets.schema.json` + lint test, `docs/wiki/declarative-views.md` | DV-4, DV-1b | 2 |
| DV-7 | web + Android: `Infantry_Views` in the compiled module list, compile both, wasm smoke against the stub | DV-3 | 5 |
| DV-8 (later, backlog) | dynamic tab source seam: one first-class tab per `place: tab` view | DV-3 | 4 |

Deferred, not ticketed: `ui.chart` and `icon` (level 2), page patches, `source` bindings (O2), placements `panel` and
`settings`, composition pages served as views, offline cache of the last page.

Tickets created for this plan: DV-1a e6fbdd6a, DV-1b 70072106, DV-2 e9ab5822, DV-3 0ea1fbe0, DV-4 98493bb3, DV-5 e3a0760b,
DV-6 16304c5e, DV-7 ebfb1a6a (all todo, claude/sonnet), DV-8 462d87fd (backlog).
