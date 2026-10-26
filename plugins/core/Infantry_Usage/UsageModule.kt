package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.UsagePane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Usage. Plan usage and limits. */
val InfantryUsage = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Usage", version = "1.0.0", author = "Infantry AI", description = "Plan usage and limits.",
        tabs = listOf(TabDeclaration("infantry.usage", TabContainers.RIGHT)),
    ),
    listOf(
        TabProvider(
            id = "infantry.usage", container = TabContainers.RIGHT, order = 40,
            title = { "Usage ^7" },
            icon = tabIcon(
                "usage",
                { moveTo(4f, 17f); arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 20f, y1 = 17f) },
                { moveTo(12f, 15f); lineTo(16f, 9f) },
            ),
            testTag = "right-tab-USAGE",
            shortcut = 7,
        ) { UsagePane(it.state, it.state.selected, it.controller) },
    ),
)
