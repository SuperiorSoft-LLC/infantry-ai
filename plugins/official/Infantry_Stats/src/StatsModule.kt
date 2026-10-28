package com.superiorsoftllc.agenthq.plugins.official.stats

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.shell.NoUsage

/** The Stats tab's id. */
const val STATS_TAB_ID = "infantry.stats"

/** The Stats tab (order 100 in the centre strip). It polls through the plugin API's usage context. */
private fun statsTab() = TabProvider(
    id = STATS_TAB_ID, container = TabContainers.CENTER, order = 100,
    title = { "Stats" },
    icon = tabIcon(
        "stats",
        { moveTo(3f, 21f); lineTo(21f, 21f) },
        { moveTo(6f, 17f); lineTo(6f, 12f) },
        { moveTo(12f, 17f); lineTo(12f, 5f) },
        { moveTo(18f, 17f); lineTo(18f, 9f) },
    ),
    testTag = "center-tab-STATS",
    routes = listOf("requestSessionStats"),
) { StatsPane(it.state, LocalShellContext.current?.usage ?: NoUsage) }

/** PLUGINS-1: Infantry_Stats (official since RUNTIME-10), the selected session's statistics, compiled in on web and Android (D9). */
val InfantryStats = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Stats", version = "1.0.0", author = "Infantry AI", description = "The selected session's statistics.",
        tabs = listOf(TabDeclaration(STATS_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(statsTab()),
)

/**
 * RUNTIME-10: the entry of Infantry_Stats (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Stats`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class StatsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(statsTab())
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryStats)
}
