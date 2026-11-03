// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
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
    /** DAEMON-PLUGINS-1: its daemon half: `loaded`, `refused: why`, `failed: why`; null = it has none. */
    val daemon: String? = null,
    /** DAEMON-PLUGINS-1: the daemon tools its daemon half registered. */
    val tools: List<String> = emptyList(),
    /** HARNESS-PLUGIN-4 H5a (1.145.0): its harness (module.json `harness`): `loaded`, `refused: why`, `failed: why`; null = it has none. */
    val harness: String? = null,
    /** H5a: the harness ids it registered (empty when its harness is not loaded). */
    val harnessIds: List<String> = emptyList(),
)

/** PLUGINS-1: `GET /plugins`: the plugins root the daemon scanned, whether a restricted policy locks it, and every module found. */
@Serializable
data class PluginsWire(val root: String, val locked: Boolean, val modules: List<PluginStatusWire>) {
    /** DAEMON-PLUGINS-1: the same with each module's daemon half as [host] holds it. */
    fun withDaemon(host: com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost): PluginsWire =
        copy(modules = modules.map { m -> host.status(m.name)?.let { m.copy(daemon = it, tools = host.toolsOf(m.name)) } ?: m })

    /** HARNESS-PLUGIN-4 H5a: the same with each module's harness as [harnesses] holds it ([HarnessModules.status]). */
    fun withHarness(harnesses: com.superiorsoftllc.infantry.daemon.plugins.HarnessModules?): PluginsWire = if (harnesses == null) this else
        copy(modules = modules.map { m -> harnesses.status(m.name)?.let { m.copy(harness = it, harnessIds = harnesses.idsOf(m.name)) } ?: m })
}

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
        /** POLICY-1: whether the active preset is restricted (official modules marked so stay off). */
        locked: Boolean = false,
        /** CONFIG-8: told the whole resolution (the daemon compiles its config manifest from it). */
        onResolved: (com.superiorsoftllc.infantry.plugin.host.Resolution) -> Unit = {},
    ): PluginsWire {
        val root = PluginFolders.root(stateDir, env, property)
        val config = PluginFolders.config(root, log)
        val resolution = ModuleResolver.resolve(PluginFolders.scan(root, config, PluginFolders.bundledRoot(env, property)), config, locked)
        log("[plugins] daemon root ${root.absolutePath}${if (locked) " (locked)" else ""}: ${resolution.loaded.size} loaded")
        resolution.log.forEach(log)
        onResolved(resolution)
        return PluginsWire(
            root.absolutePath, locked,
            resolution.statuses.map { s ->
                PluginStatusWire(s.name, s.tier.name.lowercase(), s.state.name.lowercase(), s.reason, s.manifest?.version, s.manifest?.author, s.manifest?.license)
            },
        )
    }
}
