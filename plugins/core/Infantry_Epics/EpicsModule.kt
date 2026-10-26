package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.EpicsPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleManifest
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Epics. Epics and their tickets. */
val InfantryEpics = AgentHqModule(
    ModuleManifest(name = "Infantry_Epics", version = "1.0.0", author = "Infantry AI", description = "Epics and their tickets."),
    listOf(
        TabProvider(
            id = "infantry.epics", module = "Infantry_Epics", container = TabContainers.CENTER, order = 60,
            title = { "Epics" },
            icon = tabIcon(
                "epics",
                { moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close() },
                { moveTo(3f, 12.5f); lineTo(12f, 17.5f); lineTo(21f, 12.5f) },
                { moveTo(3f, 16.5f); lineTo(12f, 21.5f); lineTo(21f, 16.5f) },
            ),
            testTag = "center-tab-EPICS",
            routes = listOf("loadEpics", "saveEpic", "addEpicTask", "moveTicket"),
        ) { EpicsPane(it.state, it.controller) },
    ),
)
