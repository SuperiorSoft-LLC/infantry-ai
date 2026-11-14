// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mcp

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.tabIcon

/** The MCP Tools tab's id. */
const val MCP_TAB_ID = "infantry.mcp"

private fun mcpTab() = TabProvider(
    id = MCP_TAB_ID, container = TabContainers.RIGHT, order = 65,
    title = { "MCP Tools" },
    icon = tabIcon("mcp-tools", { moveTo(4f, 8f); lineTo(20f, 8f); lineTo(20f, 16f); lineTo(4f, 16f); close(); moveTo(8f, 4f); lineTo(8f, 8f); moveTo(16f, 4f); lineTo(16f, 8f) }),
    testTag = "right-tab-MCP",
) { tab -> McpPane(tab) }

/** MCP-UI-1: Infantry_Mcp's UI half as a compiled-in module (web and Android; the manifest mirrors module.json). */
val InfantryMcp = InfantryModule(
    ModuleManifest(
        name = "Infantry_Mcp", version = "1.0.0", author = "Infantry AI", description = "MCP servers under the owner's control.",
        tabs = listOf(TabDeclaration(MCP_TAB_ID, TabContainers.RIGHT)),
    ),
    listOf(mcpTab()),
)

/** The entry of Infantry_Mcp's UI half (module.json `entry`): loaded at run time on desktop, compiled in on web and Android. */
class McpPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) { registrar.tab(mcpTab()) }
    override fun modules(): List<InfantryModule> = listOf(InfantryMcp)
}
