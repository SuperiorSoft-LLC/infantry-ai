// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers

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
import com.superiorsoftllc.infantry.ui.shell.NoTriggers

/** The Triggers tab's id. */
const val TRIGGERS_TAB_ID = "infantry.triggers"

/** The Triggers tab (order 50 in the center strip, under the Settings host, shortcut 0). Its requests go through the plugin API's triggers context. */
private fun triggersTab() = TabProvider(
    id = TRIGGERS_TAB_ID, container = TabContainers.CENTER, order = 50,
    title = { s -> "Triggers ${s.trippedTriggerCount.takeIf { it > 0 }?.let { "($it tripped)" } ?: ""} ^0" },
    icon = tabIcon(
        "triggers",
        { moveTo(13f, 2f); lineTo(4f, 14f); lineTo(12f, 14f); lineTo(11f, 22f); lineTo(20f, 10f); lineTo(12f, 10f); close() },
    ),
    testTag = "center-tab-TRIGGERS",
    parent = "infantry.settings", // the official Infantry_Settings host tab (RUNTIME-5)
    shortcut = 0,
    routes = listOf("saveTrigger", "deleteTrigger", "armTrigger", "disableTrigger", "fireTrigger", "loadTriggerEvents"),
) { TriggersPane(it.state, LocalShellContext.current?.triggers ?: NoTriggers) }

/** PLUGINS-1: Infantry_Triggers (official since RUNTIME-13), triggers that start work on an event, compiled in on web and Android (D9). */
val InfantryTriggers = InfantryModule(
    ModuleManifest(
        name = "Infantry_Triggers", version = "1.0.0", author = "Infantry AI", description = "Triggers that start work on an event.",
        tabs = listOf(TabDeclaration(TRIGGERS_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(triggersTab()),
)

/**
 * RUNTIME-13: the entry of Infantry_Triggers (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Triggers`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class TriggersPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(triggersTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryTriggers)
}
