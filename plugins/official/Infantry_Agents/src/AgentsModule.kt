package com.superiorsoftllc.agenthq.plugins.official

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugins.official.agents.AgentsSection
import com.superiorsoftllc.agenthq.plugin.api.PanelDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.PanelProvider
import com.superiorsoftllc.agenthq.ui.plugin.Panels
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar

/** The left panel's agents section: counters, Board, search, filters, the list (drawn from the window's `ShellContext.agents`). */
private fun agentsPanel() =
    PanelProvider(id = "infantry.agents", panel = Panels.LEFT, order = Panels.TABS_ORDER, testTag = "left-panel-agents", fill = true) { AgentsSection() }

/** Infantry_Agents as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryAgents = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Agents", version = "1.0.0", author = "Infantry AI", description = "The left panel's agents list.",
        panels = listOf(PanelDeclaration("infantry.agents", Panels.LEFT)),
    ),
    panels = listOf(agentsPanel()),
)

/**
 * RUNTIME-4: the entry of Infantry_Agents (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Agents`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class AgentsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.panel(agentsPanel())

    override fun modules(): List<AgentHqModule> = listOf(InfantryAgents)
}
