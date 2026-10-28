package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.shell.blocks.NotesTab
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Notes. The project knowledge notes. */
val InfantryNotes = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Notes", version = "1.0.0", author = "Infantry AI", description = "The project knowledge notes.",
        tabs = listOf(TabDeclaration("infantry.notes", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.notes", container = TabContainers.CENTER, order = 70,
            title = { "Notes" },
            icon = tabIcon(
                "notes",
                { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close() },
                { moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f) },
                { moveTo(9f, 13f); lineTo(16f, 13f) },
                { moveTo(9f, 17f); lineTo(16f, 17f) },
            ),
            testTag = "center-tab-MEMORY",
            parent = SETTINGS_TAB_ID,
            routes = listOf("loadMemory", "getMemory", "searchMemory", "saveMemory", "deleteMemory"),
        ) { NotesTab() },
    ),
)
