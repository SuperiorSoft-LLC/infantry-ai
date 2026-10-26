package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.PanelDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.PanelProvider
import com.superiorsoftllc.agenthq.ui.plugin.Panels

/** PLUGINS-3: core module Infantry_Agents. The left panel's agents section: counters, Board, search, filters, the list. */
val InfantryAgents = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Agents", version = "1.0.0", author = "Infantry AI", description = "The left panel's agents list.",
        panels = listOf(PanelDeclaration("infantry.agents", Panels.LEFT)),
    ),
    panels = listOf(
        PanelProvider(id = "infantry.agents", panel = Panels.LEFT, order = Panels.TABS_ORDER, testTag = "left-panel-agents", fill = true) { it.agentsList() },
    ),
)
