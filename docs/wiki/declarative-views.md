# Declarative views

A plugin can show a screen without shipping any UI code. Its daemon half answers a **view** with a JSON page; the
client (desktop, web, Android) draws that page with the widgets of `Infantry_Views`. The vocabulary lives as data in
[plugins/official/Infantry_Views/widgets.schema.json](../../plugins/official/Infantry_Views/widgets.schema.json).
The worked example is [plugins/examples/Acme_HelloView](../../plugins/examples/Acme_HelloView). Design record:
[DECL-UI-DESIGN](../DECL-UI-DESIGN.md).

## 1. What a view is

A view is a named question the client asks a module: "give me the page for `main`, with these params". The module
answers with a **page**, a tree of nodes:

```
{"schema":1,"widgets":1,"root":{"blockType":"ui.section","id":"root","config":{"title":"Hello"},"children":[ ... ]}}
```

- `schema` is the page envelope version (always 1). `widgets` is the vocabulary level the page was written for.
- A node is `{"blockType","id","config","children"}` plus optional `fallback` (text drawn by clients that do not know
  the type) and `show` (`Always`, `Wide` or `Compact`).
- Node ids are unique inside a page; lint and error messages name a node by its id.

The daemon API (1.37.0) is `api.views.provide(viewId) { ViewRequest -> ViewReply }`. A reply is `ViewReply.Page(json)`
or `ViewReply.Refused(reason)`. When the data behind a page changes, call `api.views.changed(viewId)`: the daemon
pushes `plugin_view_changed` (coalesced) and subscribed clients ask again.

## 2. module.json `daemon.views`

```
"views": [
  {"id": "main",   "title": "Hello", "place": "tab", "action": "acme.helloview.view"},
  {"id": "detail", "title": "Item",  "place": "none", "action": "acme.helloview.view"},
  {"id": "about",  "title": "About", "place": "tab", "action": "acme.helloview.view", "layout": "layout/about.json"}
]
```

| Field | Meaning |
|---|---|
| `id` | The view id. `provide` refuses ids that are not declared. |
| `title` | The label of the screen. |
| `place` | `tab`: listed as a screen. `none`: reached only by an `open` action. |
| `action` | The permission action the request is checked against. |
| `layout` | Optional static page file, served when no provider is registered. A provider wins when both exist. |
| `platforms` | Optional list of clients that show the view. Absent means all clients. |

Community modules need the grants `daemon.code`, `daemon.view` and `daemon.tool` in `plugins/modules.json`.

## 3. The vocabulary (level 1)

| Type | Takes children | Main config fields |
|---|---|---|
| `ui.section` | yes | `title`, `subtitle` |
| `ui.text` | no | `text` (required), `style`, `tone` |
| `ui.badge` | no | `text` (required), `tone` |
| `ui.stat` | no | `label`, `value` (required), `tone`, `hint` |
| `ui.list` | no | `items` (id, title, subtitle, badge, action), `empty` |
| `ui.table` | no | `columns`, `rows`, `empty` |
| `ui.button` | no | `label` (required), `action` (required), `style`, `confirm`, `enabled` |
| `ui.form` | yes | fields, validation, a submit action |
| `layout.column`, `layout.row`, `layout.stack`, `layout.group`, `layout.spacer` | yes (spacer no) | layout only |

Tones are `default`, `muted`, `good`, `warn`, `bad`. The schema file is the exact list of fields, required flags,
defaults and enum values; the example's lint test checks every page against it.

## 4. Actions

An action is exactly one of:

```
{"tool": {"name": "acme_helloview_mark", "args": {"id": "1"}, "refresh": true, "toast": false}}
{"open": {"view": "detail", "params": {"id": "1"}}}
```

- `tool` calls a tool of the view's own module (never another module's). `refresh` asks again after it returns;
  `toast` shows its result.
- `open` pushes another view of the same module onto the back stack. That is how a `place: none` view is reached.
- `confirm` on a button asks the user first; use it for anything destructive.

## 5. The versioning rule

- A level only grows by addition. Each addition raises the level by one. A breaking change is a **new widget name**
  (`ui.table2`); the old one stays forever.
- An unknown field is ignored. An unknown enum value becomes the field's default.
- An unknown `blockType` draws its `fallback` in muted style, else "<type> needs a newer app". Its children are not drawn.
- A page whose `widgets` is above the client's level is still drawn, under a banner "made for a newer version of the app".
- The daemon never checks widget names, only shape and limits.

## 6. Limits

All are named settings, not constants.

| Setting | Default | Effect |
|---|---|---|
| `limits.views.maxPageBytes` | 262144 | a larger page is refused |
| `limits.views.maxNodes` | 500 | more nodes is refused |
| `limits.views.maxDepth` | 16 | deeper nesting is refused |
| `limits.views.changedCoalesceMs` | 1000 | `changed` calls inside this window become one push |
| `limits.views.maxSubscriptionsPerConnection` | 16 | the oldest subscription is dropped |

The provider runs under the module's `callTimeoutMs` and `cpuMsPerCall`.

## 7. Desktop vs mobile

One page serves every client. Set `show` on a node to `Wide` or `Compact` to draw it on large or small screens only,
and `platforms` on a view to keep it off clients that cannot use it. Do not build two pages when one with `show` will do.

## 8. The example, step by step

`Acme_HelloView` is a to-do list with three views:

1. `main` (tab): a section with a `ui.list` of items and an "Add an item" button. Each row has an `open` action to `detail`.
2. `detail` (place `none`): needs the param `id`, otherwise it answers `Refused`. It shows a `ui.stat`, a `ui.badge`, a
   "Mark done" button and a "Remove" button with `confirm`.
3. `about` (tab): no provider; the daemon serves `layout/about.json`.

The tools `acme_helloview_mark` and `acme_helloview_remove` change the in-memory list and then call
`api.views.changed("main")`, so an open `main` page refreshes itself.

The file `Widgets.kt` in the example holds small builders (`section`, `text`, `badge`, `stat`, `list`, `button`,
`toolAction`, `openAction`). Copy it into your plugin. To start your own plugin, copy the folder and rename it:

```
plugins/examples/Acme_HelloView  ->  plugins/community/<Your_Plugin>
```

Then change the ids in `module.json`, the package and entry class, and list your views under `daemon.views`.
