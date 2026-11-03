// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.epics

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.NoEpics

/** The Epics tab's id. */
const val EPICS_TAB_ID = "infantry.epics"

/** The Epics tab (order 60 in the centre strip). Its requests go through the plugin API's epics context. */
private fun epicsTab() = TabProvider(
    id = EPICS_TAB_ID, container = TabContainers.CENTER, order = 60,
    title = { "Epics" },
    icon = tabIcon(
        "epics",
        { moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close() },
        { moveTo(3f, 12.5f); lineTo(12f, 17.5f); lineTo(21f, 12.5f) },
        { moveTo(3f, 16.5f); lineTo(12f, 21.5f); lineTo(21f, 16.5f) },
    ),
    testTag = "center-tab-EPICS",
    routes = listOf("loadEpics", "saveEpic", "addEpicTask", "moveTicket"),
) { EpicsPane(it.state, LocalShellContext.current?.epics ?: NoEpics) }

/** PLUGINS-1: Infantry_Epics (official since RUNTIME-11), epics and their tickets, compiled in on web and Android (D9). */
val InfantryEpics = InfantryModule(
    ModuleManifest(
        name = "Infantry_Epics", version = "1.0.0", author = "Infantry AI", description = "Epics and their tickets.",
        tabs = listOf(TabDeclaration(EPICS_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(epicsTab()),
)

/**
 * RUNTIME-11: the entry of Infantry_Epics (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Epics`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class EpicsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(epicsTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryEpics)
}
