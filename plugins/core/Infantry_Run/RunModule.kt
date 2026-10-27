package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import com.superiorsoftllc.agenthq.ui.shell.blocks.RunTab

/** PLUGINS-1: core module Infantry_Run. Saved commands and their runs. */
val InfantryRun = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Run", version = "1.0.0", author = "Infantry AI", description = "Saved commands and their runs.",
        tabs = listOf(TabDeclaration("infantry.run", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.run", container = TabContainers.CENTER, order = 80,
            title = { "Run" },
            icon = tabIcon(
                "run",
                { moveTo(7f, 4f); lineTo(19f, 12f); lineTo(7f, 20f); close() },
            ),
            testTag = "center-tab-RUN",
            routes = listOf("saveCommand", "deleteCommand", "startRun", "stopRun"),
        ) { RunTab() },
    ),
)
