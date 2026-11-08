# Plugin lifecycle and live swap

PLUGIN-HOTSWAP-1. How a plugin half is loaded, unloaded and swapped while the app runs, and what a plugin author does
(very little) so that a swap is clean. The code wins where this page and the code disagree.

## What a live swap does

Settings > Plugins installs, updates, enables, disables or removes one plugin. The change applies to **only that plugin
and the plugins that depend on it**:

- **Daemon half**: `DaemonPluginHost` unloads and loads the one half (PLUGIN-HOTLOAD-1). Agent sessions, the board, mail and
  every other plugin keep running; the daemon never restarts.
- **UI half (desktop)**: `DesktopPlugins.Loaded.swap` reads the plugins root again, compares each module's folder
  fingerprint (module.json, jar, the jars in its lib folder, layout files) with what is loaded, and reloads only the modules that changed.
  Every other module keeps its objects, its class loader and its UI state (open tab, scroll, drafts). The window is not
  composed again.
- **Fallback**: when a swap cannot apply the change (a compiled-in or bundled module changed, no live set yet, web and
  Android) or a swapped-out plugin stays in memory, the shell does the soft restart (PLUGIN-CONFIG-1): the whole module set
  reloads in the same process, with the same daemon connection. Agents are not affected by either path; a soft restart only
  rebuilds what the owner sees.

## Design

### Class-loader lifetime

Each plugin half has its own class loader (`PluginClassLoader` for the UI, a `ShadowJarClassLoader` for the daemon and
harness halves), never shared. A plugin's classes, its Kotlin `object`s and everything they hold live as long as that loader is reachable. An
unload therefore has one goal: leave nothing outside the plugin that points into it, then close the loader. Once nothing
points in, the GC frees the old version and the jar can be replaced (Windows keeps an open jar locked).

Unload of one UI module, in order (`DesktopPlugins`, `ModuleSet.unload`):

1. the plugin's own `UiPlugin.stop()`;
2. its [scope](#pluginscope-the-authors-side) closes: coroutines cancelled, owned resources closed, `onUnload` blocks run;
3. `SimpleEventBus.dropOwner`: every observer the module subscribed (declared observers and its own `events` subscriptions);
4. the module's entry and services leave the set; the next `TabFactory` is built without it;
5. its class loader closes; the host keeps only a weak reference to it, for the leak check.

The daemon half does the same in `DaemonPluginHost.retire`: registrations first, background work, `stop`, its scope, the
guard drains, the loader closes.

### The install folder is never held (PLUGIN-UPDATE-LOCK-1)

A class loader keeps its jars open until it is closed, and Windows refuses to move or delete an open jar or the folder
that holds it. The plugin manager moves `plugins/<tier>/<Module>` to `plugins/_versions/<Module>/<version>` on every
update, rollback and remove, and the desktop app's own manager runs in the same process as the loaded UI half: an update
of a running plugin used to fail with "is in use; stop the daemon or the app".

So no plugin class loader reads the install folder. `PluginJarShadow` copies the module's jars into this process's own
folder (`<java.io.tmpdir>/infantry-plugin-jars/<pid>/<Module>-<n>/`) and `ShadowJarClassLoader` reads the copy; a native
library is copied beside them the first time the plugin loads it. The order of an update is then:

1. the manager unloads the daemon half (its loader and copy go);
2. it moves the install folder to `_versions` and installs the new version; the running UI half is untouched and keeps
   loading classes and resources from its copy;
3. the daemon half loads the new version (a new copy), and the UI's live swap sees the changed folder fingerprint and
   loads the new version into a new loader (a new copy); the old loader closes and its copy is deleted.

Trade-off: each load copies the module's jars once (a few hundred KB for a typical plugin) instead of reading them in
place. A copy the OS still holds when its loader closes (a mapped native library, released only when the loader is
collected) is retried on the next copy, and the folder of a process that has ended is removed by the next process that
makes a copy. When no copy can be made (no space, no temp folder) the loader reads the install folder as before: the
plugin still loads, only an update while it runs may then be refused.

### Registrar handles, not host singletons

A plugin hands its contributions to the host through `UiRegistrar` (tabs, panels, blocks, observers, capabilities...).
The host keeps them in the module's `InfantryModule`, and the window reads them through the `TabFactory` built from the
loaded modules. Nothing else in the host holds a plugin object. Dropping the module from the set and building a new
`TabFactory` therefore drops every handle at once. `SimpleEventBus.subscribe` binds one observer object once, so the new
factory binds the modules that stayed without duplicating their observers.

### Compose slot unmount

All plugin UI is drawn inside host slots wrapped by `ModuleScope` (tabs, panels, slot blocks, shell overrides and
injections). `ModuleScope` keys its content by the module's load generation (`TabFactory.generation`). A swap gives the
reloaded modules a new generation: Compose disposes their old composition (its remembered values were made by the old
class loader and must not be reused by the new classes) and composes the new version afresh. A module that did not change
keeps its generation, so its composition and state survive. The shell (`InfantryShell`) composes the whole window again
only on a soft restart (its `restarts` counter), never on a live swap.

### Leak detector

After a swap the shell lets the window draw the new set (two frames, or `SWAP_SETTLE_MS`), then asks `LeakWatch.collected`
for each closed class loader: request a GC, check the weak reference, repeat until it clears or the timeout passes. A
loader still reachable means something outside the plugin holds one of its objects. Then:

- **UI half**: Settings > Plugins shows "<plugin> did not unload cleanly ...", and the shell soft-restarts to finish the
  change. Timeout: setting `INFANTRY_PLUGINS_UNLOAD_CHECK_MS` (default 5000). In dev, `-Dinfantry.plugins.leakHeapDump=true`
  writes `<state dir>/logs/plugin-leak-<module>.hprof`: open it in a heap profiler and ask for the path from the GC roots
  to the `PluginClassLoader`.
- **Daemon half**: the unload or reload answer carries the note "<plugin> did not unload cleanly: its old daemon half
  stays in memory until the next daemon start", and the daemon log says the same. The daemon is not restarted (agents
  keep running). Timeout: limit `limits.plugins.unloadCheckMs` (default 5000, 0 = no check).

A GC request is a hint: the check says "collected within the timeout", which is not proof of a leak. A false alarm costs
one soft restart, never a lost change.

### Trade-offs

| Choice | Taken | Rejected, and why |
|---|---|---|
| What reloads | the changed modules and their dependents | everything (today's soft restart): simple, but every tab loses its state |
| How a change is found | folder fingerprint diff against a fresh resolution | an explicit module id from Settings: an install does not know its module name, and the diff also catches a rebuilt jar |
| Compose state of a swapped module | disposed (generation key) | kept: the old loader's objects would leak and clash with the new classes |
| UI built from | a new immutable `TabFactory` per swap | a mutable factory: touches every reader of the factory; rebuilding is cheap and keeps it a pure value |
| Leak handling | detect, warn, fall back to the soft restart | force unload: the JVM cannot free a reachable loader |
| Lifetime API | additive `PluginScope` (plugin API revision 5, SDK 2.3.0; daemon API 1.49.0) | a required base class: breaks every plugin |

**Expensive to reverse.** `PluginScope` is public SDK surface (ABI dumps): once plugins use it, it can only grow. The
per-module class loader is the unit of unload; sharing a loader between plugins would make a single-plugin swap impossible.

## Applying a plugin change in the open window (UI-PLUGIN-BRIDGE-1)

`infantry plugin enable|disable|install|update` changes the plugins root and the daemon half (hot, PLUGIN-HOTLOAD-1); the CLI line
"takes effect at the next daemon start" is about the harness half only. The open desktop window applies the change through the
**`plugins.apply` capability** (a `runs: client` capability of Infantry_Settings, [Bridges](Bridges.md)). It runs the same
`PluginLifecycle.apply` that Settings > Plugins calls (`PluginLifecycleBridge` in `PluginDropIn.kt` hands it to the capability), so
there is one swap path. It answers `{applied, message, warnings}` when the window finishes, fails at once with "has no host" when no
window hosts it, and fails at its timeout (`timeoutMs`, 60 s) when the window does not answer.

| From | Call |
|---|---|
| CLI | `infantry tool call infantry_capability_call --payload '{"capability":"plugins.apply","args":{"id":"Acme_X","action":"enable"}}'` |
| MCP | `infantry_capability_call` with `capability: "plugins.apply"` (listed by `infantry_capability_list`) |
| Lua | `ih.call("plugins.apply", {id = "Acme_X", action = "enable"})` (`_optional = true` when no window may be open) |

`id` and `action` only label the answer: the swap applies whatever changed in the plugins root.

**Which folder the window reads.** The daemon records the plugins root it loads from in `<state dir>/plugins.root`
(`PluginFolders.recordRoot`); a desktop window on the same state dir loads from that folder (`PluginFolders.uiRoot`), unless
`INFANTRY_PLUGINS_ROOT` is set. Nothing to type. Without a recorded folder the window uses `PluginFolders.root` as before.

## PluginScope: the author's side

`PluginScope` is the plugin's lifetime, owned by the host. A UI half reads it as `UiRegistrar.scope`, a daemon half as
`DaemonApi.scope`. What you start through it stops by itself when the plugin unloads:

```kotlin
class MyPlugin : UiPlugin {
    override fun register(registrar: UiRegistrar) {
        val scope = registrar.scope
        scope.every(60_000) { refreshFeed() }                  // a timer: cancelled on unload
        scope.coroutines.launch { listenForUpdates() }          // a coroutine: cancelled on unload
        val socket = scope.own(openSocket())                    // an AutoCloseable: closed on unload
        scope.onUnload { cache.clear() }                        // any cleanup: runs once on unload
        registrar.tab(MyTab(socket))
    }
}
```

The order on unload: your `stop()` (if you kept one), then the scope: coroutines cancelled, then the owned resources and
`onUnload` blocks, newest first. Something added after unload is closed at once.

### Do

- Start coroutines, timers, sockets and listeners through `scope`.
- Keep state in your tab or panel (`remember`), in `Services.STORAGE` / `Services.SETTINGS`, or in objects your
  registration creates. It is rebuilt fresh on a swap; persisted state reloads from storage.
- Subscribe to events through `UiRegistrar.observer` or your `events` service: the host drops them for you.

### Don't

- No static mutable state that the host or another plugin can reach. Your own `object`s are fine: they go with your loader.
- No global listeners you register on JVM-wide objects (`Runtime.addShutdownHook`, AWT/Swing listeners, `Thread`s you
  start yourself) unless you add the undo to `scope.onUnload`.
- Never store a plugin object in a host singleton, a composition local you provide above your slot, or another plugin.
- Don't set a thread's context class loader to yours and leave it.

A plugin that ignores this still works: it is simply not unloaded cleanly, the leak check sees it, and the host falls back
to the soft restart for that change.

## First users and the tests that prove it

- **Read Aloud** (`Infantry_ReadAloud`, a UI-only community plugin installed from its `.ihq`): a jar module under the
  plugins root, so it swaps on the generic UI path with no change to the plugin. The path is proven on the built
  Acme_Hello jar, the same kind of module: `DesktopPluginsSwapTest` (disable, enable, update, remove; the other modules
  keep their objects; the old loader is collected) and `JvmPlatformHostSwapTest` (a leaky plugin is reported, so the
  shell falls back).
- **Infantry_Notes** (an official daemon + UI plugin): its daemon half is reloaded live while an agent session streams,
  in `SessionDuringPluginSwapTest`: every chunk arrives in order, and the daemon and its session manager are the same
  objects before and after. `DaemonPluginHostHotloadTest` covers the daemon leak note.
- `PluginLifetimeTest`: `OwnedPluginScope` close order, `SimpleEventBus.dropOwner`, `LeakWatch`.
- `PluginUpdateWhileLoadedTest` (PLUGIN-UPDATE-LOCK-1): an update applies while the old version's `PluginClassLoader` holds
  its jar open (Windows), the old version keeps reading its copy, and closing the loader deletes the copy; with the real
  Read Aloud packages on disk (`INFANTRY_REPRO_READALOUD_DIR`) it updates 1.1.0 to 1.2.0 through the manager and the live
  swap, and 1.2.0 declares `readaloud.speak`. `PluginJarShadowTest`: the copy, its clean-up and the fallback.

## Where the code is

| Part | File |
|---|---|
| `PluginScope`, `OwnedPluginScope`, `every` | `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/api/PluginScope.kt` |
| `LeakWatch` | `superiorSoftLibrary/infantryPlugins/src/jvmMain/kotlin/com/superiorsoftllc/infantry/plugin/host/LeakWatch.kt` |
| jar copies (`PluginJarShadow`, `ShadowJarClassLoader`) | `superiorSoftLibrary/infantryPlugins/src/jvmMain/kotlin/com/superiorsoftllc/infantry/plugin/host/PluginJarShadow.kt` |
| per-owner observers (`SimpleEventBus.dropOwner`) | `superiorSoftLibrary/infantryPlugins/src/commonMain/kotlin/com/superiorsoftllc/infantry/plugin/host/ServiceRegistry.kt` |
| UI swap (`DesktopPlugins.Loaded.swap`) | `superiorSoftLibrary/infantryUiCore/src/jvmMain/kotlin/com/superiorsoftllc/infantry/ui/plugin/DesktopPlugins.kt` |
| generation key | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/plugin/ModuleScope.kt` |
| shell swap and fallback (`PluginLifecycle`, `PluginSwap`) | `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/shell/InfantryShell.kt`, `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/shell/PluginDropIn.kt` |
| desktop host (`JvmPlatformHost.swapPlugins`) | `superiorSoftLibrary/infantryUiCore/src/jvmMain/kotlin/com/superiorsoftllc/infantry/ui/shell/JvmPlatformHost.kt` |
| daemon half unload and leak note | `superiorSoftLibrary/infantry/src/main/kotlin/com/superiorsoftllc/infantry/daemon/plugins/DaemonPluginHost.kt` |
| Settings > Plugins | `plugins/official/Infantry_Settings/src/PluginsScreen.kt` |
