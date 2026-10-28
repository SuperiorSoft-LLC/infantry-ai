package com.superiorsoftllc.agenthq.plugins.official.notes

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** The Notes tab's id. */
const val NOTES_TAB_ID = "infantry.notes"

/** The Notes tab (order 70 in the centre strip), a setup tab under the official Infantry_Settings host tab (RUNTIME-5). */
private fun notesTab() = TabProvider(
    id = NOTES_TAB_ID, container = TabContainers.CENTER, order = 70,
    title = { "Notes" },
    icon = tabIcon(
        "notes",
        { moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f); lineTo(6f, 21f); close() },
        { moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f) },
        { moveTo(9f, 13f); lineTo(16f, 13f) },
        { moveTo(9f, 17f); lineTo(16f, 17f) },
    ),
    testTag = "center-tab-MEMORY",
    parent = "infantry.settings",
    routes = listOf("loadMemory", "getMemory", "searchMemory", "saveMemory", "deleteMemory"),
) { NotesTab() }

/**
 * PLUGINS-1: Infantry_Notes (official since RUNTIME-8), the project knowledge notes, as a compiled-in module (web and
 * Android, D9; the manifest mirrors module.json). It brings its own tab tree, [ShellNotes], which the tab draws (RUNTIME-8:
 * no longer registered by Infantry_AppUi).
 */
val InfantryNotes = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Notes", version = "1.0.0", author = "Infantry AI", description = "The project knowledge notes.",
        tabs = listOf(TabDeclaration(NOTES_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(notesTab()),
    tabTrees = mapOf(NOTES_TAB_ID to ShellNotes),
)

/**
 * RUNTIME-8: the entry of Infantry_Notes (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Notes`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class NotesPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(notesTab())
        registrar.tabTree(NOTES_TAB_ID, ShellNotes)
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryNotes)
}
