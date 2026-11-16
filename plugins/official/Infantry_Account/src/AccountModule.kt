// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.plugin.TabGroups
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.circle
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import kotlinx.serialization.json.JsonPrimitive

/** The Account tab's id: a host tab. A plugin adds a section by declaring a tab whose `parent` is this id (any order above [ACCOUNT_SECTION_ORDER_BASE]). */
const val ACCOUNT_TAB_ID = "infantry.account"


private fun accountIcon() = tabIcon(
    "account",
    { circle(12f, 8f, 4f) },
    { moveTo(4f, 21f); lineTo(4f, 19f); lineTo(8f, 15f); lineTo(16f, 15f); lineTo(20f, 19f); lineTo(20f, 21f); close() },
)

/** The Services host tab (id and order kept from the Account tab): the list of services and, for a connected one, its dashboard. */
private fun accountTab() = TabProvider(
    id = ACCOUNT_TAB_ID, container = TabContainers.CENTER, order = 190,
    title = { "Services" }, icon = accountIcon(), testTag = "center-tab-ACCOUNT",
    routes = listOf("GET state", "POST signin", "POST signout"),
) { ServicesHub(it) }

/**
 * ACCOUNT-1: Infantry_Account (official, installable, not bundled), compiled in on web and Android (D9; the manifest mirrors
 * module.json). Nested sub-tabs inside a section are NOT used here: this tab has one level of sections, and other plugins add
 * sections by naming it as their tab's `parent` (cross-module sub-tabs, the way Settings works). A second level has never been
 * drawn anywhere; that is seam S3 and is left to the plugin that needs it.
 */
val InfantryAccount = InfantryModule(
    ModuleManifest(
        name = "Infantry_Account", version = "1.0.0", author = "Infantry AI",
        description = "The Services tab: sign in to services that register as providers, and open their dashboards.",
        tabs = listOf(TabDeclaration(ACCOUNT_TAB_ID, TabContainers.CENTER)),
        config = listOf(
            ConfigDeclaration(
                ACCOUNT_TAB_ID, "Tabs",
                listOf(ConfigFieldDeclaration(TabGroups.GROUPED, ConfigFieldTypes.CHECKBOX, "Show a service's dashboard section inside the Services tab", default = JsonPrimitive(true))),
                tab = "Appearance", order = 70, title = "Account sections", keywords = listOf("account", "tabs", "sections", "sub-tabs"),
            ),
        ),
    ),
    listOf(accountTab()),
)

/** The entry of Infantry_Account (module.json `entry`): desktop loads it from its signed jar as a [UiPlugin]; web and Android compile it in as a [ModuleSource]. */
class AccountPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(accountTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryAccount)
}
