package com.superiorsoftllc.agenthq.plugins.official.run

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** The Run tab's id. */
const val RUN_TAB_ID = "infantry.run"

/** The Run tab (order 80 in the centre strip), a setup tab under the official Infantry_Settings host tab (RUNTIME-5). */
private fun runTab() = TabProvider(
    id = RUN_TAB_ID, container = TabContainers.CENTER, order = 80,
    title = { "Run" },
    icon = tabIcon(
        "run",
        { moveTo(7f, 4f); lineTo(19f, 12f); lineTo(7f, 20f); close() },
    ),
    testTag = "center-tab-RUN",
    parent = "infantry.settings",
    routes = listOf("saveCommand", "deleteCommand", "startRun", "stopRun"),
) { RunTab() }

/**
 * PLUGINS-1: Infantry_Run (official since RUNTIME-7), saved commands and their runs, as a compiled-in module (web and
 * Android, D9; the manifest mirrors module.json). It brings its own tab tree, [ShellRun], which the tab draws (RUNTIME-7:
 * no longer registered by Infantry_AppUi).
 */
val InfantryRun = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Run", version = "1.0.0", author = "Infantry AI", description = "Saved commands and their runs.",
        tabs = listOf(TabDeclaration(RUN_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(runTab()),
    tabTrees = mapOf(RUN_TAB_ID to ShellRun),
)

/**
 * RUNTIME-7: the entry of Infantry_Run (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Run`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class RunPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(runTab())
        registrar.tabTree(RUN_TAB_ID, ShellRun)
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryRun)
}
