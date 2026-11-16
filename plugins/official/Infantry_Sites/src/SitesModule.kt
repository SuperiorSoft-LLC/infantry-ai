// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.tabIcon

/** Infantry_Account's tab id (a host tab): a section joins the Account tab by naming it as its `parent`. Order 30 sorts after Overview (10) and Billing (20). */
const val SITES_ACCOUNT_PARENT = "infantry.account"
const val SITES_TAB_ID = "infantry.sites"
const val SITES_SECTION_ORDER = 30

private fun sitesIcon() = tabIcon(
    "sites",
    { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 10f); lineTo(3f, 10f); close() },
    { moveTo(3f, 14f); lineTo(21f, 14f); lineTo(21f, 19f); lineTo(3f, 19f); close() },
)

/**
 * The Sites section. Nested sub-sections (Sites > Servers) have not been drawn anywhere yet (ACCOUNT-HOSTING 1.6), so Servers is
 * drawn directly in this section; the later Sites / Domains / Activity sections hang under it when a second level exists.
 */
private fun sitesTab() = TabProvider(
    id = SITES_TAB_ID, container = TabContainers.CENTER, order = SITES_SECTION_ORDER,
    title = { "Sites" }, icon = sitesIcon(), testTag = "center-tab-SITES", parent = SITES_ACCOUNT_PARENT,
    routes = listOf("GET state"),
) { SitesServers(it) }

val InfantrySites = InfantryModule(
    ModuleManifest(
        name = "Infantry_Sites", version = "1.0.0", author = "Infantry AI",
        description = "The Sites section of the Account tab: hosting servers.",
        tabs = listOf(TabDeclaration(SITES_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(sitesTab()),
)

/** The entry of Infantry_Sites (module.json `entry`): desktop loads it from its signed jar as a [UiPlugin]; web and Android compile it in as a [ModuleSource]. */
class SitesPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(sitesTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantrySites)
}
