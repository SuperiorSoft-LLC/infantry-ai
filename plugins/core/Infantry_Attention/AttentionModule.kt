package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.AttentionPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Attention. What needs the owner. */
val InfantryAttention = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Attention", version = "1.0.0", author = "Infantry AI", description = "What needs the owner.",
        tabs = listOf(TabDeclaration("infantry.attention", TabContainers.RIGHT)),
    ),
    listOf(
        TabProvider(
            id = "infantry.attention", container = TabContainers.RIGHT, order = 30,
            title = { s -> "Attention ${s.attentionOpenCount.takeIf { it > 0 }?.let { "($it)" } ?: ""} ^6" },
            icon = tabIcon(
                "attention",
                { moveTo(6f, 16f); lineTo(6f, 11f); curveTo(6f, 7f, 8.5f, 5f, 12f, 5f); curveTo(15.5f, 5f, 18f, 7f, 18f, 11f); lineTo(18f, 16f); lineTo(20f, 18f); lineTo(4f, 18f); close() },
                { moveTo(10f, 21f); lineTo(14f, 21f) },
            ),
            testTag = "right-tab-ATTENTION",
            shortcut = 6,
            routes = listOf("ackAttention", "answerQuestion", "select"),
        ) { AttentionPane(it.state, it.appController, it.showChat) },
    ),
)

/** RUNTIME-1: the entry of Infantry_Attention (module.json `entry`): hands over this module alone. */
class AttentionModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantryAttention)
}
