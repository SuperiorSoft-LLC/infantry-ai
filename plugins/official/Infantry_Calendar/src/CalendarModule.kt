// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.tabIcon

/** The Calendar tab's id. */
const val CALENDAR_TAB_ID = "infantry.calendar"

private fun calendarTab() = TabProvider(
    id = CALENDAR_TAB_ID, container = TabContainers.CENTER, order = 60,
    title = { "Calendar" },
    icon = tabIcon(
        "calendar",
        { moveTo(4f, 6f); lineTo(20f, 6f); lineTo(20f, 20f); lineTo(4f, 20f); close() },
        { moveTo(4f, 10f); lineTo(20f, 10f) },
        { moveTo(8f, 3f); lineTo(8f, 7f) },
        { moveTo(16f, 3f); lineTo(16f, 7f) },
    ),
    testTag = "center-tab-CALENDAR",
) { CalendarTab(it) }

/** CAL-2: Infantry_Calendar's UI half as a compiled-in module (web and Android, D9; the manifest mirrors module.json). */
val InfantryCalendar = InfantryModule(
    ModuleManifest(
        name = "Infantry_Calendar", version = "1.0.0", author = "Infantry AI", description = "A planner for the user: tasks and reminders.",
        tabs = listOf(TabDeclaration(CALENDAR_TAB_ID, TabContainers.CENTER)),
        routes = CALENDAR_ROUTES, events = listOf(CALENDAR_CHANGED_EVENT), daemonFrames = listOf("calendar_changed"),
        services = listOf("events", "daemon"),
    ),
    listOf(calendarTab()),
)

/** The entry of Infantry_Calendar (module.json `entry`): desktop loads it from its signed jar as a [UiPlugin], web and Android compile it in as a [ModuleSource]. */
class CalendarPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) { registrar.tab(calendarTab()) }
    override fun modules(): List<InfantryModule> = listOf(InfantryCalendar)
}
