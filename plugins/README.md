# Plugins (modules)

New here? [GUIDE.md](GUIDE.md) gets you from the starter template (`_template/`) to a working module in about
10 minutes.

Infantry AI is built from modules, Magento style. Every tab you see is a module's contribution. The window never
names a tab: it asks a factory for the tabs of a container (`center`, `right`) and draws the one picked.

```
plugins/
  modules.json            the folders to scan and the owner's enable list (edit and restart, no rebuild)
  core/<Vendor_Module>/   built-in modules, compiled into every build (desktop, web, Android)
  official/<Vendor_Module>/  programs made by Infantry: a jar signed by the Infantry key, loaded at runtime (desktop)
  community/<Vendor_Module>/  modules added by dropping a folder here (desktop and daemon only)
```

## Trust label, not a sandbox

The folder decides a module's tier; a `"tier"` written in module.json is logged and ignored. **Core** and
**Community** are trust labels. They are not a sandbox. A JVM module runs inside the app with the app's full access
to the machine. Enable a community module only if you trust whoever wrote it.

These rules are enforced:

- A community module is off until `modules.json` enables it. It needs `author` and `license` in its module.json, or
  it is refused.
- A community module can add tabs and hook into them (interceptors, observers, slot blocks). It cannot replace a core
  tab unless `modules.json` lists that tab under the module's `allowOverride`.
- Community modules are labelled Community, with their author and license, in the log, on their tabs and in
  Settings > Plugins > Modules.
- A module reaches the app only through the platform services handed to it (`notifications`, `storage`, `events`,
  `settings`, `daemon`). Each service must be declared in module.json. The owner can deny any service, and a
  community module also needs each one listed under `allow`.
- Daemon access covers only the routes the module declares (`"GET /health"`). Under the demo lock it is also limited
  to `demoLock.allowRoutes`.
- With `AGENTHQ_DEMO_LOCK` set, no community module loads; core is unchanged. The route rule applies to the `daemon`
  service; the built-in core tabs talk to the daemon through the app's own connection, exactly as without the lock.
- A core update never touches `community/`.
- An **official** module (`official/`, in the plugins root or in the install's bundled modules root, `-Dagenthq.plugins.bundled`
  / `AGENTHQ_BUNDLED_MODULES`) is on by default and its declared services are granted without `allow`, but it loads
  through the same filtered class loader as community and gets no core right. Its jar must be signed by the Infantry
  key (a certificate in `trusted-keys/`) and carry a signed `module.json` that claims `"tier": "official"` and equals the
  one on disk; otherwise it is refused. Dev runs may skip that with `-Dagenthq.plugins.devTrust=true` (never set by the
  packaged launcher). Safe mode skips official modules too. A modules.json without an `official` folder still scans it.

A module that has invalid JSON, a missing dependency, a dependency cycle, a different `apiVersion`, a wrong name, or a
jar that will not load is skipped, with one log line saying why. It never stops the app.

## module.json

```json
{
  "name": "Acme_Hello",
  "version": "1.0.0",
  "apiVersion": 1,
  "author": "Acme",
  "license": "<your license id>",
  "description": "What it does.",
  "dependencies": [{ "id": "Infantry_Cli", "version": ">=1.0 <2" }],
  "sequence": [],
  "entry": "com.acme.hello.HelloPlugin",
  "jar": "build/libs/Acme_Hello.jar",
  "routes": ["GET /health"],
  "events": ["tab.opened"],
  "services": ["events", "storage", "daemon", "notifications"],
  "tabs": [{ "id": "acme.hello", "container": "center" }],
  "panels": [{ "id": "acme.side", "panel": "left" }],
  "preferences": [{ "replace": "acme.other", "with": "acme.hello" }],
  "interceptors": [{ "target": "infantry.epics", "name": "hi", "kind": "around" }],
  "observers": [{ "event": "tab.opened", "name": "log" }],
  "blocks": [{ "slot": "center.top", "name": "banner", "order": 0 }]
}
```

- `dependencies` (PLUGINS-2) lists the modules this one cannot run without, each with a version range (`>=1.2 <2`,
  `^1.2`, `~1.2.3`, `1.2`, `*`, alternatives joined by `||`). A dependency that is missing, off, refused or out of
  range refuses the module. A core module may depend on core modules only. Settings > Plugins enforces the same
  rules before it writes modules.json: it will not enable a module until its dependencies are enabled (it names
  them, in enable order), and it will not disable one that enabled modules need unless cascade is ticked.
- `sequence` sets load order only: a module named there loads first if it loads at all. The load order is a
  topological sort of `dependencies` plus `sequence`.
- `tabs` go in a container: `center` or `right` (the right panel's strip). `panels` add a section to a side panel:
  `left` or `right`.
- `preferences` replace a contribution by id: a tab with one of your tabs, or a panel section (for example the core
  `infantry.agents` list) with one of your panel sections. The replacement takes the replaced one's place (id, order,
  shortcut, test tag; for a section also its panel and fill). If two modules replace the same id, the last one loaded
  wins, and the replacement is logged. A tab cannot replace a section, or the other way round.
- `interceptors` hook a tab. `kind` decides which parts of the registered `TabInterceptor` run:
  - `before`: only `before`, drawn above the tab's content;
  - `after`: only `after`, drawn below it;
  - `around` (the default): every part. `title` wraps the tab's title and `content` wraps its content (call `proceed`
    to draw the original, or leave it out to replace it); `before` and `after` draw as above.

  A part that the kind does not run is not run, and the log says which. An unknown kind refuses the interceptor.
- `blocks` add content to a slot. Every tab container has two slots: `center.top`, `center.bottom`, `right.top` and
  `right.bottom`. They draw above or below the content of every tab in that container, in `order`. A block in any
  other slot is not drawn, and the log names the slot once at load.
- `events` the window publishes, for `observers` (each one needs the `events` service and the event in `events`):
  - `tab.opened` (`id`, `container`): a tab is shown;
  - `session.selected` (`id`): an agent is selected;
  - `modules.loaded` (`modules`, comma-separated in load order; `count`): once, when the window has bound its modules;
  - `ticket.changed` (`id`, `status`, `title`, `change` = `created`, `updated` or `removed`): one per ticket that a
    board update changes. The first board a window receives is the baseline, so it publishes nothing.
- `entry` is a class implementing `com.superiorsoftllc.agenthq.ui.plugin.UiPlugin`. It registers its tabs, panel
  sections, interceptors, blocks and observers by the names declared above. Anything it registers without declaring
  is refused.
- The API (manifest, services, events, the loading rules) lives in `superiorSoftLibrary/agenthqPlugins`. It depends
  on nothing in the app. Whole screens, a module's own state, and background work are reserved contribution types and
  will come the same way.

## modules.json

```json
{
  "folders": [{ "path": "core", "tier": "core" }, { "path": "community", "tier": "community" }],
  "modules": {
    "Acme_Hello": { "enabled": true, "allow": ["events", "storage"], "deny": [], "allowOverride": [] }
  },
  "demoLock": { "allowRoutes": ["GET /health"] }
}
```

The plugins root is `AGENTHQ_PLUGINS_ROOT`, else the `agenthq.plugins.root` system property, else
`<state dir>/plugins`. The Gradle dev runs (`run`, `runRealDaemon`, `runTabShots`) point it at this folder.

## Adding a community module

Copy `_template/` (see [GUIDE.md](GUIDE.md)), or create `plugins/community/<Vendor_Module>/` containing a `module.json` and either a prebuilt jar or a
`build.gradle.kts`. Nothing else in the repository changes. The build finds a module with sources by scanning this
folder, and the loader finds every module the same way. No list anywhere names a plugin. A folder whose name starts
with `_` is skipped by both. Enable the module in Settings > Plugins > Modules or in `modules.json`. See `community/Acme_Hello` for an example.

## Platforms

- **Desktop and the headless daemon** load modules at run time from the plugins root. The daemon applies the same rules
  and lists the result at `GET /plugins`.
- **Web and Android** run the compiled-in core modules only. They cannot load a jar at run time, so a community module
  would need a build step that compiles it in, and none exists yet. Drop-in is desktop and daemon first.
