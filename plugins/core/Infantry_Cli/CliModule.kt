package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Cli. The selected agent's chat transcript. */
val InfantryCli = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Cli", version = "1.0.0", author = "Infantry AI", description = "The selected agent's chat transcript.",
        tabs = listOf(TabDeclaration("infantry.cli", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.cli", container = TabContainers.CENTER, order = 10,
            title = { "CLI  ^1" },
            icon = tabIcon(
                "cli",
                { moveTo(5f, 7f); lineTo(10f, 12f); lineTo(5f, 17f) },
                { moveTo(12f, 17f); lineTo(19f, 17f) },
            ),
            testTag = "center-tab-OUTPUT",
            shortcut = 1,
        ) { it.transcript() },
    ),
)
