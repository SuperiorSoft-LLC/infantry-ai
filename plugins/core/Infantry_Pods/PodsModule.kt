package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.PodsPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Pods. Pods: submit a goal, watch its nodes. */
val InfantryPods = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Pods", version = "1.0.0", author = "Infantry AI", description = "Pods: submit a goal, watch its nodes.",
        tabs = listOf(TabDeclaration("infantry.pods", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.pods", container = TabContainers.CENTER, order = 20,
            title = { "Pods  ^2" },
            icon = tabIcon(
                "pods",
                { moveTo(12f, 3f); lineTo(20f, 7.5f); lineTo(20f, 16.5f); lineTo(12f, 21f); lineTo(4f, 16.5f); lineTo(4f, 7.5f); close() },
                { moveTo(4f, 7.5f); lineTo(12f, 12f); lineTo(20f, 7.5f) },
                { moveTo(12f, 12f); lineTo(12f, 21f) },
            ),
            testTag = "center-tab-PODS",
            shortcut = 2,
            routes = listOf("submitPod", "queryPod", "cancelPod", "select"),
        ) { PodsPane(it.state, it.controller) },
    ),
)
