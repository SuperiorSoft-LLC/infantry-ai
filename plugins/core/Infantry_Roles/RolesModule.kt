package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.RolesPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Roles. Roles: who does what, and starting a session as one. */
val InfantryRoles = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Roles", version = "1.0.0", author = "Infantry AI", description = "Roles: who does what, and starting a session as one.",
        tabs = listOf(TabDeclaration("infantry.roles", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.roles", container = TabContainers.CENTER, order = 30,
            title = { "Roles  ^5" },
            icon = tabIcon(
                "roles",
                { circle(12f, 8f, 4f) },
                { moveTo(4f, 21f); curveTo(4f, 16f, 8f, 14f, 12f, 14f); curveTo(16f, 14f, 20f, 16f, 20f, 21f) },
            ),
            testTag = "center-tab-ROLES",
            shortcut = 5,
            routes = listOf("saveRole", "saveRoleBinding", "saveProfile", "deleteProfile", "spawnAs", "select"),
        ) { RolesPane(it.state, it.controller, onSessionStarted = it.showChat, onOpenAgent = it::openAgent) },
    ),
)
