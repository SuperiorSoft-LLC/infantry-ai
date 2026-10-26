package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.plugin.host.ModuleResolver
import com.superiorsoftllc.agenthq.plugin.host.PluginFolders
import kotlinx.serialization.Serializable
import java.io.File

/** PLUGINS-1: one module as `GET /plugins` reports it. */
@Serializable
data class PluginStatusWire(
    val name: String,
    val tier: String,
    val state: String,
    val reason: String? = null,
    val version: String? = null,
    val author: String? = null,
    val license: String? = null,
)

/** PLUGINS-1: `GET /plugins`: the plugins root the daemon scanned, whether the demo lock is on, and every module found. */
@Serializable
data class PluginsWire(val root: String, val demoLock: Boolean, val modules: List<PluginStatusWire>)

/**
 * PLUGINS-1: the headless daemon's view of the modules. It applies the same folders, modules.json and trust rules as
 * the desktop loader (a module that fails them is logged and skipped, never fatal), so a module's background work
 * has a place to land. The daemon has no UI contributions to load, so nothing is put on its class path yet.
 */
object DaemonPlugins {
    fun resolve(
        stateDir: File,
        env: (String) -> String? = System::getenv,
        property: (String) -> String? = System::getProperty,
        log: (String) -> Unit = { System.err.println(it) },
    ): PluginsWire {
        val root = PluginFolders.root(stateDir, env, property)
        val config = PluginFolders.config(root, log)
        val demoLock = PluginFolders.demoLock(env)
        val resolution = ModuleResolver.resolve(PluginFolders.scan(root, config), config, demoLock)
        log("[plugins] daemon root ${root.absolutePath}${if (demoLock) " (demo lock on)" else ""}: ${resolution.loaded.size} loaded")
        resolution.log.forEach(log)
        return PluginsWire(
            root.absolutePath, demoLock,
            resolution.statuses.map { s ->
                PluginStatusWire(s.name, s.tier.name.lowercase(), s.state.name.lowercase(), s.reason, s.manifest?.version, s.manifest?.author, s.manifest?.license)
            },
        )
    }
}
