package com.superiorsoftllc.infantry.plugins.official.pods

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
import com.superiorsoftllc.infantry.ui.shell.NoPods

/** The Pods tab's id. */
const val PODS_TAB_ID = "infantry.pods"

/** The Pods tab (order 20 in the center strip, shortcut 2). Its requests go through the plugin API's pods context. */
private fun podsTab() = TabProvider(
    id = PODS_TAB_ID, container = TabContainers.CENTER, order = 20,
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
) { PodsPane(it.state, LocalShellContext.current?.pods ?: NoPods) }

/** PLUGINS-1: Infantry_Pods (official since RUNTIME-12), submit a goal and watch its nodes, compiled in on web and Android (D9). */
val InfantryPods = InfantryModule(
    ModuleManifest(
        name = "Infantry_Pods", version = "1.0.0", author = "Infantry AI", description = "Pods: submit a goal, watch its nodes.",
        tabs = listOf(TabDeclaration(PODS_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(podsTab()),
)

/**
 * RUNTIME-12: the entry of Infantry_Pods (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Pods`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class PodsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(podsTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryPods)
}
