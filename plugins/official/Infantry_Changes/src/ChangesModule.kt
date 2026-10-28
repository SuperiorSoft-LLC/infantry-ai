package com.superiorsoftllc.agenthq.plugins.official.changes

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** The Changes tab's id. */
const val CHANGES_TAB_ID = "infantry.changes"

/** The Changes tab (order 10 in the right strip, shortcut 3). */
private fun changesTab() = TabProvider(
    id = CHANGES_TAB_ID, container = TabContainers.RIGHT, order = 10,
    title = { "Changes ^3" },
    icon = tabIcon(
        "changes",
        { moveTo(12f, 4f); lineTo(12f, 12f) },
        { moveTo(8f, 8f); lineTo(16f, 8f) },
        { moveTo(8f, 18f); lineTo(16f, 18f) },
    ),
    testTag = "right-tab-CHANGES",
    shortcut = 3,
    routes = listOf("loadProjectWorktrees", "loadProjectBranches", "loadBranchChanges", "loadProjectTree", "loadWorktreeStatus", "selectChangesBranch", "selectChangesWorktree", "pollOnce"),
) { ChangesTab(it.diffTarget, it.onOpenDiff) }

/**
 * PLUGINS-1: Infantry_Changes (official since RUNTIME-8), the project's worktrees and their changes, as a compiled-in
 * module (web and Android, D9; the manifest mirrors module.json). It brings its own tab tree, [ShellChanges], which the tab
 * draws (RUNTIME-8: no longer registered by Infantry_AppUi).
 */
val InfantryChanges = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Changes", version = "1.0.0", author = "Infantry AI", description = "The project's worktrees and their changes.",
        tabs = listOf(TabDeclaration(CHANGES_TAB_ID, TabContainers.RIGHT)),
    ),
    listOf(changesTab()),
    tabTrees = mapOf(CHANGES_TAB_ID to ShellChanges),
)

/**
 * RUNTIME-8: the entry of Infantry_Changes (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Changes`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class ChangesPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(changesTab())
        registrar.tabTree(CHANGES_TAB_ID, ShellChanges)
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryChanges)
}
