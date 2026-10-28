package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.TriggersPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Triggers. Triggers that start work on an event. */
val InfantryTriggers = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Triggers", version = "1.0.0", author = "Infantry AI", description = "Triggers that start work on an event.",
        tabs = listOf(TabDeclaration("infantry.triggers", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.triggers", container = TabContainers.CENTER, order = 50,
            title = { s -> "Triggers ${s.trippedTriggerCount.takeIf { it > 0 }?.let { "($it tripped)" } ?: ""} ^0" },
            icon = tabIcon(
                "triggers",
                { moveTo(13f, 2f); lineTo(4f, 14f); lineTo(12f, 14f); lineTo(11f, 22f); lineTo(20f, 10f); lineTo(12f, 10f); close() },
            ),
            testTag = "center-tab-TRIGGERS",
            parent = "infantry.settings", // the official Infantry_Settings host tab (RUNTIME-5)
            shortcut = 0,
            routes = listOf("saveTrigger", "deleteTrigger", "armTrigger", "disableTrigger", "fireTrigger", "loadTriggerEvents"),
        ) { TriggersPane(it.state, it.appController) },
    ),
)

/** RUNTIME-1: the entry of Infantry_Triggers (module.json `entry`): hands over this module alone. */
class TriggersModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantryTriggers)
}
