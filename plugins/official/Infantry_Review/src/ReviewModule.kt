// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.review

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.circle
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.NoReview

/** The Review tab's id. */
const val REVIEW_TAB_ID = "infantry.review"

/** The Review tab (order 20 in the right strip, shortcut 4). Opening an agent goes through the plugin API's review context. */
private fun reviewTab() = TabProvider(
    id = REVIEW_TAB_ID, container = TabContainers.RIGHT, order = 20,
    title = { s -> "Review ${s.reviewQueue.size.takeIf { it > 0 }?.let { "($it)" } ?: ""} ^4" },
    icon = tabIcon(
        "review",
        { moveTo(2f, 12f); curveTo(5f, 6f, 19f, 6f, 22f, 12f); curveTo(19f, 18f, 5f, 18f, 2f, 12f); close() },
        { circle(12f, 12f, 3f) },
    ),
    testTag = "right-tab-REVIEW",
    shortcut = 4,
    routes = listOf("select"),
) { ReviewPane(it.state, LocalShellContext.current?.review ?: NoReview) }

/** PLUGINS-1: Infantry_Review (official since RUNTIME-11), pod nodes waiting for review, compiled in on web and Android (D9). */
val InfantryReview = InfantryModule(
    ModuleManifest(
        name = "Infantry_Review", version = "1.0.0", author = "Infantry AI", description = "Pod nodes waiting for review.",
        tabs = listOf(TabDeclaration(REVIEW_TAB_ID, TabContainers.RIGHT)),
    ),
    listOf(reviewTab()),
)

/**
 * RUNTIME-11: the entry of Infantry_Review (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Review`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class ReviewPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(reviewTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryReview)
}
