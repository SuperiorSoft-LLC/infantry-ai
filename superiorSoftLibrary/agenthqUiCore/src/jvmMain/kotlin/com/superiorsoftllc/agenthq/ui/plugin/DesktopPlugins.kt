package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Notifications
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.DependencyGraph
import com.superiorsoftllc.agenthq.plugin.host.FilePluginSettings
import com.superiorsoftllc.agenthq.plugin.host.FilePluginStorage
import com.superiorsoftllc.agenthq.plugin.host.ModuleCandidate
import com.superiorsoftllc.agenthq.plugin.host.ModuleChange
import com.superiorsoftllc.agenthq.plugin.host.ModuleResolver
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugin.host.ModulesFile
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.plugin.host.Resolution
import com.superiorsoftllc.agenthq.plugin.host.RouteGate
import com.superiorsoftllc.agenthq.plugin.host.ServiceRegistry
import com.superiorsoftllc.agenthq.plugin.host.SimpleEventBus
import com.superiorsoftllc.agenthq.plugin.host.provideEvents

import com.superiorsoftllc.agenthq.ui.model.ToastKind
import java.io.File
import java.net.URLClassLoader

/**
 * PLUGINS-1: the desktop's module loader. Finds modules only by scanning the configured subfolders of the plugins root
 * (no list anywhere names a plugin), applies the trust rules ([ModuleResolver]), then loads each community module's
 * jar and entry class into this JVM. A jar that will not load refuses that module (and the ones that need it) with a
 * log line; nothing here stops the app. The compiled-in core modules always take part, from disk or not.
 *
 * This is a trust label, not a sandbox: a loaded jar runs in-process with the app's full access.
 */
object DesktopPlugins {
    class Loaded(
        val factory: TabFactory,
        val root: File,
        val config: ModulesConfig,
        val demoLock: Boolean,
        val log: List<String>,
        /** The gated services each loaded module gets (what its tabs see as [TabContext.services]). */
        val services: (module: String) -> com.superiorsoftllc.agenthq.plugin.api.PluginServices,
    )

    fun load(
        stateDir: File,
        env: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
        compiled: List<AgentHqModule> = CompiledModules.all,
        print: (String) -> Unit = { System.err.println(it) },
    ): Loaded {
        val log = mutableListOf<String>()
        val say: (String) -> Unit = { log += it; print(it) }
        val root = PluginFolders.root(stateDir, env, property)
        val config = PluginFolders.config(root, say)
        val demoLock = PluginFolders.demoLock(env)
        val candidates = candidates(root, config, compiled)
        say("[plugins] root ${root.absolutePath}${if (demoLock) " (demo lock on)" else ""}")
        var resolution = ModuleResolver.resolve(candidates, config, demoLock)

        var built: TabFactory? = null
        val bus = SimpleEventBus(say)
        val registry = ServiceRegistry(config, say).apply {
            provideEvents(bus, say)
            provide(Services.NOTIFICATIONS) { s ->
                Notifications { title, body ->
                    say("[plugins] ${s.name} notifies: $title - $body")
                    // TOAST-1: and the window's toast (a no-op until a window has set its controller's).
                    built?.toasts?.post(body, ToastKind.INFO, title)
                }
            }
            provide(Services.STORAGE) { s -> FilePluginStorage(dataDir(stateDir, s.name)) }
            provide(Services.SETTINGS) { s -> FilePluginSettings(FilePluginStorage(dataDir(stateDir, s.name))) }
            provide(Services.DAEMON) { s -> RouteGate.gated(s.manifest!!, demoLock, config, PluginDaemon::send) }
        }
        val services = resolution.loaded.associate { it.name to registry.forModule(it) }

        val modules = mutableListOf<AgentHqModule>()
        val refused = HashSet<String>()
        for (s in resolution.loaded) {
            val m = s.manifest!!
            val needs = m.dependencies.map { it.id }.firstOrNull { it in refused }
            val result = if (needs != null) Result.failure(IllegalStateException("dependency $needs is refused")) else
                compiled.firstOrNull { it.manifest.name == s.name && s.tier == Tier.CORE }?.let { Result.success(it) }
                    ?: runCatching { loadJar(s, services.getValue(s.name)) }
            result.onSuccess { modules += it }.onFailure {
                refused += s.name
                resolution = resolution.refuse(s.name, it.message ?: it::class.simpleName.orEmpty())
            }
        }
        resolution.log.forEach(say)
        val preferences = resolution.preferences.filter { it.module !in refused }
        val shellOverrides = resolution.shellOverrides.filter { it.module !in refused }
        val changes = ModuleChanges { request -> change(root, compiled, demoLock, request).also { c -> (c as? ModuleChange.Applied)?.log?.forEach(say); say("[plugins] ${c.message}") } }
        val factory = TabFactory(modules, preferences, services = { services[it] ?: NoPluginServices }, events = bus, statuses = statuses(resolution), changes = changes, shellOverrides = shellOverrides,
            allowInject = { config.setting(it).allowInject }, allowOverride = { config.setting(it).allowOverride })
        built = factory
        factory.problems.forEach { say("[plugins] $it") }
        return Loaded(factory, root, config, demoLock, log) { services[it] ?: NoPluginServices }
    }

    /** What is installed: every module folder under the root, plus each compiled-in core module that has none. */
    private fun candidates(root: File, config: ModulesConfig, compiled: List<AgentHqModule>): List<ModuleCandidate> {
        val onDisk = PluginFolders.scan(root, config)
        // A compiled-in core module with no folder on disk (the installed app) still loads, as core.
        return onDisk + compiled.filter { c -> onDisk.none { it.folderName == c.manifest.name } }
            .map { ModuleCandidate(it.manifest.name, Tier.CORE, null, it.manifest) }
    }

    /**
     * PLUGINS-2: Settings asks to enable or disable a module. modules.json is read afresh, the dependency rules decide,
     * and only an allowed change is written (it applies at the next start). Under the demo lock nothing is written.
     */
    fun change(root: File, compiled: List<AgentHqModule>, demoLock: Boolean, request: ModuleRequest): ModuleChange {
        if (demoLock) return ModuleChange.Refused("The demo lock is on: modules.json is not changed.")
        val config = PluginFolders.config(root)
        val graph = DependencyGraph.of(candidates(root, config, compiled), config)
        val result = if (request.enable) graph.enable(request.module, config) else graph.disable(request.module, config, request.cascade)
        if (result is ModuleChange.Applied && result.changed.isNotEmpty()) ModulesFile.write(root, config, result.config)
        return result
    }

    /** Loaded first, in load order, then the rest by name: the order Settings > Plugins lists them. */
    private fun statuses(r: Resolution): List<ModuleStatus> =
        r.statuses.filter { it.state == ModuleState.LOADED } + r.statuses.filter { it.state != ModuleState.LOADED }.sortedBy { it.name }

    /** A module's private data area: `<stateDir>/plugin-data/<Vendor_Module>`. */
    fun dataDir(stateDir: File, module: String) = File(File(stateDir, "plugin-data"), module)

    private fun loadJar(s: ModuleStatus, services: com.superiorsoftllc.agenthq.plugin.api.PluginServices): AgentHqModule {
        val m = s.manifest!!
        val dir = File(s.dir ?: error("no folder"))
        val entry = m.entry?.takeIf { it.isNotBlank() } ?: error("module.json has no entry class")
        val jar = File(dir, m.jar?.takeIf { it.isNotBlank() } ?: error("module.json has no jar"))
        if (!jar.isFile) error("jar ${m.jar} not found (build it first)")
        if (!jar.canonicalPath.startsWith(dir.canonicalPath + File.separator)) error("jar ${m.jar} is outside the module folder")
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), DesktopPlugins::class.java.classLoader)
        val plugin = try {
            loader.loadClass(entry).getDeclaredConstructor().newInstance() as? UiPlugin ?: error("$entry is not a UiPlugin")
        } catch (e: ReflectiveOperationException) {
            error("entry $entry would not load (${e::class.simpleName})")
        } catch (e: LinkageError) {
            error("entry $entry would not link (${e::class.simpleName}: ${e.message})")
        }
        return registerModule(m, s.tier, services, plugin)
    }
}
