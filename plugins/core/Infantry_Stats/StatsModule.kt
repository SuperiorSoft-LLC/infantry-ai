package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.StatsPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Stats. The selected session's statistics. */
val InfantryStats = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Stats", version = "1.0.0", author = "Infantry AI", description = "The selected session's statistics.",
        tabs = listOf(TabDeclaration("infantry.stats", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.stats", container = TabContainers.CENTER, order = 100,
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
        ) { StatsPane(it.state, it.appController) },
    ),
)
