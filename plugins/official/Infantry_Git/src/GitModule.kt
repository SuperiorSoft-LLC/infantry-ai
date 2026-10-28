package com.superiorsoftllc.agenthq.plugins.official.git

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** The Git tab's id. */
const val GIT_TAB_ID = "infantry.git"

/** The Git tab (order 90 in the centre strip), a setup tab under the official Infantry_Settings host tab (RUNTIME-5). */
private fun gitTab() = TabProvider(
    id = GIT_TAB_ID, container = TabContainers.CENTER, order = 90,
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
    parent = "infantry.settings",
    routes = listOf("loadProjectGit", "loadProjectBranches", "loadProjectDiff", "checkoutProjectBranch", "setProjectBaseBranch", "saveProjectDirectory"),
) { GitTab() }

/**
 * PLUGINS-1: Infantry_Git (official since RUNTIME-6), the project's git state, as a compiled-in module (web and Android,
 * D9; the manifest mirrors module.json). It brings its own tab tree, [ShellGit], which the tab draws (RUNTIME-6: no
 * longer registered by Infantry_AppUi).
 */
val InfantryGit = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Git", version = "1.0.0", author = "Infantry AI", description = "The project's git state.",
        tabs = listOf(TabDeclaration(GIT_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(gitTab()),
    tabTrees = mapOf(GIT_TAB_ID to ShellGit),
)

/**
 * RUNTIME-6: the entry of Infantry_Git (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Git`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class GitPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(gitTab())
        registrar.tabTree(GIT_TAB_ID, ShellGit)
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryGit)
}
