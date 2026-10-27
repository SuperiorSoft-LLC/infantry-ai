# Writing a plugin: the 10-minute guide

A plugin, or module, is a folder with a `module.json` and a jar. Infantry AI finds it by scanning its plugin folders.
No list anywhere names it. [README.md](README.md) covers the rules in depth. This page gets you to a working
module.

## 1. Copy the template (1 min)

```
cp -r plugins/_template plugins/community/Acme_Notes
```

In `plugins/community/Acme_Notes/module.json`, set `"name": "Acme_Notes"`, `"jar": "build/libs/Acme_Notes.jar"`,
your `author` and your `license`. Rename the package in `src/` and point `entry` at the new class. Every field is
explained in [_template/README.md](_template/README.md).

## 2. Folder layout

```
plugins/
  modules.json                 the folders to scan and the owner's enable list
  GUIDE.md                     this page
  _template/                   the starter (a name starting with _ is never loaded)
  core/<Vendor_Module>/        built-in modules, compiled into every build
  community/<Vendor_Module>/   your modules: module.json, build.gradle.kts, src/, build/libs/<Vendor_Module>.jar
```

## 3. Declare what you need

- **dependencies**: `[{ "id": "Infantry_Cli", "version": ">=1.0 <2" }]`. List the modules yours cannot run without.
  Ranges take `>= > <= < =`, a bare version (`1.2` means any 1.2.x), `^1.2.3`, `~1.2.3`, `*`, and alternatives
  joined by `||`. The rules:
  - A missing, disabled or out-of-range dependency refuses your module at load. The reason is shown in Settings.
  - Settings will not enable your module until its dependencies are enabled. It tells you which ones to enable
    first, and in what order.
  - A module that enabled modules depend on cannot be disabled or removed. Settings names those modules, unless you
    tick cascade, which disables them too (off by default, and logged).
  - A core module may depend on core modules only. Cycles are refused.
- **sequence** sets load order only. **dependencies** is for what you require.
- **services and permissions**: list the services you use under `services` (`notifications`, `storage`, `settings`,
  `events`, `daemon`). The owner grants each one to a community module in `modules.json`, under
  `"allow": [...]`, and can take any of them away with `"deny"`. An ungranted service is `null` in your code.
  The `daemon` service calls only the routes you list under `routes`.

## Where your module shows

Everything is declared in `module.json` and registered under the same id. Anything not declared is refused.

- **Main tabs**: `"tabs": [{ "id": "acme.notes", "container": "center" }]` plus `registrar.tab(TabProvider(...))`.
  `order` places it in the strip. The core tabs leave gaps of 10.
- **Right panel tabs**: the same, with `"container": "right"`. They behave exactly like main tabs: the same
  factory, ordering, enable and disable, and Community badge.
- **Panel sections**: `"panels": [{ "id": "acme.notes.side", "panel": "left" }]` plus
  `registrar.panel(PanelProvider(id, panel, order, testTag) { ... })`. Use `"left"` or `"right"`. A section stacks
  by `order`. Below 100 it sits above the agents list (left) or the tab strip (right). At 100 or more it sits below
  them. `fill = true` makes the section share the panel's free height. The left panel's agents list is itself the
  core module `Infantry_Agents`.
- **Shell nodes** (replace or wrap a piece of the window itself): `"shell": [{ "node": "infantry.shell.footer",
  "name": "myBar", "mode": "wrap" }]` plus `registrar.shell("myBar", BlockOverride { scope, inner -> ... })`. The
  window is a tree of blocks, each with a node id (the list is pinned in `CoreShellTreeTest`; the tree is in
  `docs/BLOCKS-LAYOUT-DESIGN.md` section 2). `node` takes the id or its address `infantry.shell#<id>`.
  - `replace`: your block draws in the node's place (`inner` is null). `wrap`: you draw around the core block, which
    you get as `inner`; call `inner.Render(scope)` where it goes. Apply `scope.modifier` exactly once, on your
    outermost node: it carries the placement the parent gave the node (a weight, a fill).
  - Several modules on one node apply in load order: wraps nest with the last loaded outermost, the last replace wins.
  - **Locked** nodes cannot be replaced or wrapped: the skeleton (`window, images, dock, frame, body, panes,
    windowLayer`) and `connect, servers, switchError, serverUpdate, config`. An unknown id, an unknown mode or a locked
    node is ignored, and the reason is one line in Settings > Plugins and the log. A community module needs the node
    id in its `allowOverride` in `modules.json`.
  - **Node ids are API.** They are never renamed; a module may rely on them. Ids not in the pinned list are not
    stable.

Both tab strips scroll sideways when the tabs do not fit. Arrows at each end, the mouse wheel and touch all scroll
them, so a module's tab is never hidden.

## 4. Build and drop in (3 min)

```
./gradlew :plugins:community:Acme_Notes:jar :plugins:community:Acme_Notes:test
```

The build finds any `plugins/community/*/build.gradle.kts` by itself. The jar holds only your classes, because the
app provides the plugin API, Compose and Kotlin. To install on another machine, copy the folder (with
`module.json` and `build/libs/Acme_Notes.jar`) into `<plugins root>/community/`. The plugins root is
`AGENTHQ_PLUGINS_ROOT`, else `<state dir>/plugins`.

## 5. Enable it (1 min)

Open Settings > Plugins > Modules and press Enable on your module, then restart. You can also add
`"Acme_Notes": { "enabled": true, "allow": ["notifications"] }` under `modules` in `modules.json`. Each row shows
`needs:` and `required by:`. If your module did not load, its row says why in one line, and so does the log
(`[plugins] Acme_Notes (community): refused - ...`).

## Trust tiers: a label, not a sandbox

The folder decides the tier. **Core** is what ships with the app. **Community** is anything dropped into
`community/`. A community module is off until it is enabled, must name its author and license, is labelled
Community wherever it shows, and may not replace a core tab or a shell node unless `allowOverride` permits it. A loaded jar still
runs inside the app with the app's full access to the machine. The tier is a trust label, not a sandbox: enable only
modules whose authors you trust.

## Web and Android

The browser and Android clients run the compiled-in core modules only. They cannot load a jar at run time, so a
community module works on the desktop app and the headless daemon (`GET /plugins` lists what it resolved).

## The demo lock

With `AGENTHQ_DEMO_LOCK` set, no community module loads, Settings cannot change modules.json, and the `daemon`
service of a core module is further limited to `demoLock.allowRoutes` in `modules.json`. Core tabs work as usual.

## Proving the existing UI did not change

A module or seam that must leave the rest of the window as it was proves it with
`./gradlew :superiorSoftLibrary:agentHqUi:diffTabShots`: every tab is rendered and compared pixel by pixel with the
committed baseline, and the task fails on any difference. See "Proving pixel-identical" in
`superiorSoftLibrary/agentHqUi/README.md`.
