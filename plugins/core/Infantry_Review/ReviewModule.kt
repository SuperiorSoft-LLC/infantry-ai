package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.ReviewPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Review. Pod nodes waiting for review. */
val InfantryReview = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Review", version = "1.0.0", author = "Infantry AI", description = "Pod nodes waiting for review.",
        tabs = listOf(TabDeclaration("infantry.review", TabContainers.RIGHT)),
    ),
    listOf(
        TabProvider(
            id = "infantry.review", container = TabContainers.RIGHT, order = 20,
            title = { s -> "Review ${s.reviewQueue.size.takeIf { it > 0 }?.let { "($it)" } ?: ""} ^4" },
            icon = tabIcon(
                "review",
                { moveTo(2f, 12f); curveTo(5f, 6f, 19f, 6f, 22f, 12f); curveTo(19f, 18f, 5f, 18f, 2f, 12f); close() },
                { circle(12f, 12f, 3f) },
            ),
            testTag = "right-tab-REVIEW",
            shortcut = 4,
            routes = listOf("select"),
        ) { ReviewPane(it.state, it.controller) },
    ),
)
