// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugins.official.agents.GLOBAL_WORKERS_KEY
import com.superiorsoftllc.infantry.plugins.official.agents.GLOBAL_WORKERS_NODE
import kotlinx.serialization.json.JsonPrimitive
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugins.official.agents.AgentsSection
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.PanelProvider
import com.superiorsoftllc.infantry.ui.plugin.Panels
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

/** The left panel's agents section: counters, Board, search, filters, the list (drawn from the window's `ShellContext.agents`). */
private fun agentsPanel() =
    PanelProvider(id = "infantry.agents", panel = Panels.LEFT, order = Panels.TABS_ORDER, testTag = "left-panel-agents", fill = true) { AgentsSection() }

/** Infantry_Agents as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryAgents = InfantryModule(
    ModuleManifest(
        name = "Infantry_Agents", version = "1.0.0", author = "Infantry AI", description = "The left panel's agents list.",
        panels = listOf(PanelDeclaration("infantry.agents", Panels.LEFT)),
        config = listOf(
            // WORKERS-GLOBAL-1: the experimental all-projects Working view, off by default.
            ConfigDeclaration(
                node = GLOBAL_WORKERS_NODE, group = "Experimental", tab = "Agents", order = 900, title = "Experimental",
                applies = "Applies to: the Working counter at the top of the left panel.",
                keywords = listOf("experimental", "working", "workers", "projects", "lanes"),
                fields = listOf(
                    ConfigFieldDeclaration(GLOBAL_WORKERS_KEY, "checkbox", "Click Working to see the agents working in every project (experimental)", JsonPrimitive(false), order = 1, scope = "device"),
                ),
            ),
            ConfigDeclaration(node = "infantry.shell.config.progressStale", group = "Progress bars: stale after", tab = "General", order = 80, title = "Progress bars: stale after", keywords = listOf("progress", "stale", "minutes"), block = "infantry.shell.config.progressStale"),
            ConfigDeclaration(node = "infantry.shell.config.cliLocations", group = "CLI locations", tab = "Agents", order = 100, title = "CLI locations", keywords = listOf("harness", "install", "path", "cli"), block = "infantry.shell.config.cliLocations"),
        ),
    ),
    panels = listOf(agentsPanel()),
)

/**
 * RUNTIME-4: the entry of Infantry_Agents (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Agents`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class AgentsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.panel(agentsPanel())

    override fun modules(): List<InfantryModule> = listOf(InfantryAgents)
}
