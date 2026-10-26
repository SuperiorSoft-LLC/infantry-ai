package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.PromptsPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleManifest
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Prompts. Saved prompts to insert into the chat. */
val InfantryPrompts = AgentHqModule(
    ModuleManifest(name = "Infantry_Prompts", version = "1.0.0", author = "Infantry AI", description = "Saved prompts to insert into the chat."),
    listOf(
        TabProvider(
            id = "infantry.prompts", module = "Infantry_Prompts", container = TabContainers.RIGHT, order = 50,
            title = { "Prompts" },
            icon = tabIcon(
                "prompts",
                { moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(10f, 16f); lineTo(6f, 20f); lineTo(6f, 16f); lineTo(4f, 16f); close() },
            ),
            testTag = "right-tab-PROMPTS",
            routes = listOf("loadPrompts", "savePrompt", "deletePrompt"),
        ) { PromptsPane(it.state, it.controller, onInsert = it.insertIntoChat) },
    ),
)
