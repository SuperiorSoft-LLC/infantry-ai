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
const val ACCOUNT_OVERVIEW_TAB_ID = "infantry.account.overview"
const val ACCOUNT_BILLING_TAB_ID = "infantry.account.billing"

/** Overview is 10 and Billing 20; sections other plugins add sort after them. */
const val ACCOUNT_SECTION_ORDER_BASE = 20

private fun accountIcon() = tabIcon(
    "account",
    { circle(12f, 8f, 4f) },
    { moveTo(4f, 21f); lineTo(4f, 19f); lineTo(8f, 15f); lineTo(16f, 15f); lineTo(20f, 19f); lineTo(20f, 21f); close() },
)

/** The Account host tab (centre strip, order 190): it draws only its sections. */
private fun accountTab() = TabProvider(
    id = ACCOUNT_TAB_ID, container = TabContainers.CENTER, order = 190,
    title = { "Account" }, icon = accountIcon(), testTag = "center-tab-ACCOUNT",
) { it.SubTabs(ACCOUNT_TAB_ID) }

private fun overviewTab() = TabProvider(
    id = ACCOUNT_OVERVIEW_TAB_ID, container = TabContainers.CENTER, order = 10,
    title = { "Overview" }, icon = accountIcon(), testTag = "center-tab-ACCOUNT-OVERVIEW", parent = ACCOUNT_TAB_ID,
    routes = listOf("GET state", "POST signin", "POST signout"),
) { AccountOverview(it) }

private fun billingTab() = TabProvider(
    id = ACCOUNT_BILLING_TAB_ID, container = TabContainers.CENTER, order = ACCOUNT_SECTION_ORDER_BASE,
    title = { "Billing" }, icon = accountIcon(), testTag = "center-tab-ACCOUNT-BILLING", parent = ACCOUNT_TAB_ID,
    routes = listOf("GET state"),
) { AccountBilling(it) }

/**
 * ACCOUNT-1: Infantry_Account (official, installable, not bundled), compiled in on web and Android (D9; the manifest mirrors
 * module.json). Nested sub-tabs inside a section are NOT used here: this tab has one level of sections, and other plugins add
 * sections by naming it as their tab's `parent` (cross-module sub-tabs, the way Settings works). A second level has never been
 * drawn anywhere; that is seam S3 and is left to the plugin that needs it.
 */
val InfantryAccount = InfantryModule(
    ModuleManifest(
        name = "Infantry_Account", version = "1.0.0", author = "Infantry AI",
        description = "The Account tab: sign in to services that register as account providers.",
        tabs = listOf(TabDeclaration(ACCOUNT_TAB_ID, TabContainers.CENTER), TabDeclaration(ACCOUNT_OVERVIEW_TAB_ID, TabContainers.CENTER), TabDeclaration(ACCOUNT_BILLING_TAB_ID, TabContainers.CENTER)),
        config = listOf(
            ConfigDeclaration(
                ACCOUNT_TAB_ID, "Tabs",
                listOf(ConfigFieldDeclaration(TabGroups.GROUPED, ConfigFieldTypes.CHECKBOX, "Show the Account sections (Overview, Billing and the ones plugins add) inside the Account tab", default = JsonPrimitive(true))),
                tab = "Appearance", order = 70, title = "Account sections", keywords = listOf("account", "tabs", "sections", "sub-tabs"),
            ),
        ),
    ),
    listOf(accountTab(), overviewTab(), billingTab()),
)

/** The entry of Infantry_Account (module.json `entry`): desktop loads it from its signed jar as a [UiPlugin]; web and Android compile it in as a [ModuleSource]. */
class AccountPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(accountTab())
        registrar.tab(overviewTab())
        registrar.tab(billingTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryAccount)
}
