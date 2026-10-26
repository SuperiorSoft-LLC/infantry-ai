package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Notifications
import com.superiorsoftllc.agenthq.plugin.api.Services
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.FilePluginSettings
import com.superiorsoftllc.agenthq.plugin.host.FilePluginStorage
import com.superiorsoftllc.agenthq.plugin.host.ModuleCandidate
import com.superiorsoftllc.agenthq.plugin.host.ModuleResolver
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import com.superiorsoftllc.agenthq.plugin.host.Resolution
import com.superiorsoftllc.agenthq.plugin.host.RouteGate
import com.superiorsoftllc.agenthq.plugin.host.ServiceRegistry
import com.superiorsoftllc.agenthq.plugin.host.SimpleEventBus
import com.superiorsoftllc.agenthq.plugin.host.provideEvents
import com.superiorsoftllc.agenthq.plugins.core.CoreModules
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
    class Loaded(val factory: TabFactory, val root: File, val config: ModulesConfig, val demoLock: Boolean, val log: List<String>)

    fun load(
        stateDir: File,
        env: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
        compiled: List<AgentHqModule> = CoreModules.all,
        print: (String) -> Unit = { System.err.println(it) },
    ): Loaded {
        val log = mutableListOf<String>()
        val say: (String) -> Unit = { log += it; print(it) }
        val root = PluginFolders.root(stateDir, env, property)
        val config = PluginFolders.config(root, say)
        val demoLock = PluginFolders.demoLock(env)
        val onDisk = PluginFolders.scan(root, config)
        // A compiled-in core module with no folder on disk (the installed app) still loads, as core.
        val candidates = onDisk + compiled.filter { c -> onDisk.none { it.folderName == c.manifest.name } }
            .map { ModuleCandidate(it.manifest.name, Tier.CORE, null, it.manifest) }
        say("[plugins] root ${root.absolutePath}${if (demoLock) " (demo lock on)" else ""}")
        var resolution = ModuleResolver.resolve(candidates, config, demoLock)

        val bus = SimpleEventBus(say)
        val registry = ServiceRegistry(config, say).apply {
            provideEvents(bus, say)
            provide(Services.NOTIFICATIONS) { s -> Notifications { title, body -> say("[plugins] ${s.name} notifies: $title - $body") } }
            provide(Services.STORAGE) { s -> FilePluginStorage(dataDir(stateDir, s.name)) }
            provide(Services.SETTINGS) { s -> FilePluginSettings(FilePluginStorage(dataDir(stateDir, s.name))) }
            provide(Services.DAEMON) { s -> RouteGate.gated(s.manifest!!, demoLock, config, PluginDaemon::send) }
        }
        val services = resolution.loaded.associate { it.name to registry.forModule(it) }

        val modules = mutableListOf<AgentHqModule>()
        val refused = HashSet<String>()
        for (s in resolution.loaded) {
            val m = s.manifest!!
            val needs = m.sequence.firstOrNull { it in refused }
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
        val factory = TabFactory(modules, preferences, services = { services[it] ?: NoPluginServices }, events = bus, statuses = statuses(resolution))
        factory.problems.forEach { say("[plugins] $it") }
        return Loaded(factory, root, config, demoLock, log)
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
