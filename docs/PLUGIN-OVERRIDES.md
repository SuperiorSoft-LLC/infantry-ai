# PLUGIN-OVERRIDES: change the app from a plugin, without editing core (OVERRIDE-1)

A plugin can replace a block, wrap it, or place its own block before or after it. Nothing in core is edited, and removing the
plugin brings the original back.

**What already existed (BLOCKS-LAYOUT L5, BLOCKS-INJECTORS J1/J2, CONFIG-10b) and what this ticket added.**
Before: `shell[]` replace/wrap by plain node id, `inject[]` into a parent (`first|last|before:<child>|after:<child>`),
`fills[].replaces` (a whole fill, by module name), `allowOverride` / `allowInject` grants, `CoreShell.locked`. All of it
resolved once when the module set changes (`BlockResolver`), not per frame. Gaps, verified in the code: no path selectors, no
priority (last loaded won), no `before`/`after` of a *target* (only of a child inside a named parent), and **the compile-time
path (web/Android, `TabFactory(modules)`) dropped `shell[]` entirely** because only the desktop loader supplied the list.
Added: selectors, `priority`, modes `before`/`after`, the compile-time path, the demo module set (`-Pdemo=true`).
We extended the existing `shell` key rather than add a second `overrides` key: one declaration, one resolver, old manifests unchanged.

## 1. Addressing (selector grammar)

```
selector := id | path
id       := a block id, e.g. infantry.shell.footer           (the block with that id anywhere in the tree)
path     := segment ("/" segment)*                           (each segment the DIRECT parent of the next)
segment  := id | "[" n "]"                                   ([n] = the n-th child, from 0, of the segment before it)
```
- `infantry.shell.frame/infantry.shell.body`: the `body` block whose parent is `frame`. A path need not start at the root.
- `infantry.shell.frame/[1]`: the second child of `frame`.
- `infantry.shell#<id>` (the old address) is still a plain id. First match in tree order wins; ids are unique in a tree.
- Matcher: `NodeSelector.find(root, selector)` in `block/layout/Resolver.kt`. A selector that matches nothing is ignored with a log line.

## 2. Declaring (module.json + code)

```json
"shell": [
  { "node": "infantry.shell.frame/infantry.shell.body", "name": "banner", "mode": "before", "priority": 10 }
]
```
```kotlin
class MyPlugin : UiPlugin {
    override fun register(r: UiRegistrar) = r.shell("banner", BlockOverride { scope, inner ->
        // replace/before/after: inner == null.  wrap: inner.Render(scope) draws the original.
        Text("hello", modifier = scope.modifier)
    })
}
```
`UiRegistrar.shell` has a default no-op, so old plugins are unaffected.

| mode | effect |
|---|---|
| `replace` (default) | the original is not drawn; the override takes its id and place |
| `wrap` | the override draws around the original (`inner.Render(scope)`) |
| `before` / `after` | the override is drawn as the previous/next sibling of the target in its parent; the original is untouched (the block id is `<module>.<mode>-<target id>`) |

## 3. Resolution

Resolved once per module set (`BlockResolver.resolve`, cached in `TabFactory`), never per frame. The override gets the node's
scope; a wrap also gets the original with its children and config.
- **replace**: higher `priority` wins; equal priority: the later loaded module. Each loser is logged by name.
- **wrap**: applied in (priority, load order), so the highest is outermost.
- **before/after**: all render, ordered by priority (higher first) then load order.
- **A throwing override.** Compose cannot catch an exception thrown while a block draws (try/catch around composable calls is not
  allowed), so a render-time fallback to the original is *not* built. Problems that can be caught are: an unknown selector or mode,
  a missing registration, a locked target, a parent that takes no children: each skips the override and logs. See open question in the report.

## 4. Trust

- Core and official modules may override or insert anywhere except a **locked** block.
- A community module needs an explicit grant in modules.json: `allowOverride` lists the selector or node id it may use. Without it the
  override is refused (logged); the loader applies this on desktop, the factory applies the same rule on the compile-time path.
- **Non-overridable = `CoreShell.locked`** (the trust sections, the Config dialog, About, the drop-in consent, safe mode, the window/dock).
  A locked block refuses replace, wrap, a `before`/`after` next to it, and injection into it. We did not add a separate
  `overridable: false` key: `locked` already is that set; to make a block non-overridable, add its id there.

## 5. Removing the plugin

The tree is rebuilt from the base tree and the current module list. A module that is gone contributes nothing, so the original
returns (when the module set changes; no other plugin restarts). Tested: `removing the plugin restores the original layout`.

## 6. Demo wasm / Android versus production

- `plugins/demo/<Demo_X>/` are ordinary modules (own `build.gradle.kts`, `module.json`, a `ModuleSource` entry).
- `generateModuleList` (agenthqAppUi) lists them in the compiled-in module list **only with `-Pdemo=true`**, after the live modules, so
  on equal priority a demo override wins ("demo over live", D16), and their config goes into `GeneratedConfig` the same way.
  Desktop never lists them. Production wasm/Android (no flag) are byte-for-byte the live set.
- The compile-time path has no loader, so `TabFactory(modules)` reads `shell[]` off the manifests and applies the same trust rule.
- Concrete demo override: `plugins/demo/Demo_Banner` puts a strip ("DEMO BUILD - sample data") before `infantry.shell.frame/infantry.shell.body`.
- Build the demo: `gradlew -Pdemo=true :superiorSoftLibrary:agentHqWeb:<your wasm bundle task>`; without `-Pdemo` the banner is absent.


### 6a. Turning features off: policy presets, not overrides (POLICY-1)

An override changes how a node is drawn. To turn features **off** (a demo, a guest or read-only deployment) do not override
the panes: declare named `actions` in module.json and ship a policy preset as data (docs/POLICY.md). The daemon enforces
the preset and sends it to every client; core's gate (`Gated`, `ActionButton`, `canWrite`) dims what it turns off, in
every build, and one setting (Configuration > General > Policy, `AGENTHQ_POLICY`, or a preset's `selectWhenEnv`, e.g.
`AGENTHQ_DEMO_LOCK=1`) switches it. `plugins/demo/Demo_Lock` is the worked example: its `policies/demo.json` holds every
demo value, and its override keeps only the notice for `Demo_Banner`.

## 7. Settings

The factory's `problems` list already shows what an override did or refused. `NodeRegistry` (Settings > Plugins > Registered) lists a node's
replace/wrap overrides and injections per container. The sibling `before`/`after` placements appear there as injections (`Injection`
source). A dedicated read-only "who overrides what" list, with the path selectors resolved, is not built in this ticket.
The settings dialog replacement of CONFIG-10b (`fills[].replaces`) is kept as is: it replaces a whole *fill by module*, which a node-level
override cannot express; both coexist, and the node-level one can wrap/replace what the fill draws.

## 8. Daemon side (read-only, nothing built)

Facts (DaemonPlugins.kt, PLUGIN-ENDPOINTS-DESIGN.md): the daemon applies the same folders, modules.json and trust rules as the desktop loader
(`ModuleResolver`), logs and skips what fails them, and **puts nothing on its class path** ("no UI contributions to load"). What a daemon-side
plugin can do today: its manifest feeds the daemon's compiled config manifest (config sections, CONFIG-8) and `GET /plugins` reports it; client plugins
reach the daemon through `PluginDaemon.send` past `RouteGate`, which lets a route through only if listed (`routes`, and `demoLock.allowRoutes`).
It cannot declare a new route, run code, or add a tool/CLI command.
Smallest additive step to let a plugin add a daemon tool: a module.json key `daemonTools: [{name, jar entry}]`, a daemon-side loader that
puts the signed plugin jar on a *separate* class loader, and one registry interface (`DaemonTool { name; schema; call(args) }`) the MCP/CLI layer enumerates,
gated by the same tier/grant rule (`allowDaemonTool`). Owner decision needed before building (isolation and credentials: PLUGIN-ENDPOINTS D1).

## 9. The licence split

Core and plugins can live in separate directories under different licences because every change a user wants (replace a block, put a block
before/after another, change a demo) is a plugin that points at a core block by id or path. The user's plugin lives in `community/` or their own
folder and never edits core files; core stays MPL, the user's override stays theirs, and the locked set is the only part they cannot change.
