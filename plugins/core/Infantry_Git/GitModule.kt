package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import com.superiorsoftllc.agenthq.ui.shell.blocks.GitTab

/** PLUGINS-1: core module Infantry_Git. The project's git state. */
val InfantryGit = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Git", version = "1.0.0", author = "Infantry AI", description = "The project's git state.",
        tabs = listOf(TabDeclaration("infantry.git", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.git", container = TabContainers.CENTER, order = 90,
            title = { "Git" },
            icon = tabIcon(
                "git",
                { circle(6f, 5f, 2f) },
                { circle(6f, 19f, 2f) },
                { circle(18f, 7f, 2f) },
                { moveTo(6f, 7f); lineTo(6f, 17f) },
                { moveTo(18f, 9f); curveTo(18f, 14f, 6f, 12f, 6f, 17f) },
            ),
            testTag = "center-tab-GIT",
            routes = listOf("loadProjectGit", "loadProjectBranches", "loadProjectDiff", "checkoutProjectBranch", "setProjectBaseBranch", "saveProjectDirectory"),
        ) { GitTab() },
    ),
)
