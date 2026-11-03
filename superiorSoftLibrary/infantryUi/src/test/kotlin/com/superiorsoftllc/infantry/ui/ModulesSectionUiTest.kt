// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.DependencyDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleChange
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.ui.plugin.MODULE_BYLINE_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_CASCADE_TAG
import com.superiorsoftllc.infantry.ui.plugin.MODULE_EMPTY_TAG
import com.superiorsoftllc.infantry.ui.plugin.MODULE_REASON_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_ROW_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_STATE_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_TIER_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_MESSAGE_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_NEEDS_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_REQUIRED_BY_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_TOGGLE_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.ModuleChanges
import com.superiorsoftllc.infantry.ui.plugin.ModuleRequest
import com.superiorsoftllc.infantry.ui.plugin.ModulesSection
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLUGINS-2: Settings > Plugins > Modules shows needs / required by, and shows the rules' answer under the row. */
@OptIn(ExperimentalTestApi::class)
class ModulesSectionUiTest {
    private val cli = ModuleManifest("Infantry_Cli", "1.0.0")
    private val hello = ModuleManifest("Acme_Hello", "1.0.0", author = "Acme", license = "NOASSERTION", dependencies = listOf(DependencyDeclaration("Infantry_Cli", ">=1.0 <2")))
    private val statuses = listOf(
        ModuleStatus("Infantry_Cli", Tier.CORE, ModuleState.LOADED, manifest = cli),
        ModuleStatus("Acme_Hello", Tier.COMMUNITY, ModuleState.LOADED, manifest = hello),
    )

    @Test
    fun `needs and required-by are shown, a refusal reads under the row, cascade is off by default`() = runDesktopComposeUiTest(900, 600) {
        val asked = mutableListOf<ModuleRequest>()
        val changes = ModuleChanges { r ->
            asked += r
            if (r.module == "Infantry_Cli" && !r.cascade) ModuleChange.Refused("Cannot disable Infantry_Cli: enabled modules depend on it: Acme_Hello.", dependents = listOf("Acme_Hello"))
            else ModuleChange.Applied(ModulesConfig(), listOf("Acme_Hello", "Infantry_Cli"), "Infantry_Cli disabled; cascade also disabled Acme_Hello. Restart to apply.")
        }
        setContent { ModulesSection(TabFactory(emptyList(), statuses = statuses, changes = changes)) }

        onNodeWithTag(MODULE_NEEDS_TAG_PREFIX + "Acme_Hello").assertTextEquals("Needs: Infantry_Cli >=1.0 <2")
        onNodeWithTag(MODULE_REQUIRED_BY_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Required by: Acme_Hello")
        onNodeWithTag(MODULE_CASCADE_TAG).assertIsOff()

        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Infantry_Cli").performClick()
        onNodeWithTag(MODULE_MESSAGE_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Cannot disable Infantry_Cli: enabled modules depend on it: Acme_Hello.")
        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Disable")

        onNodeWithTag(MODULE_CASCADE_TAG).performClick()
        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Infantry_Cli").performClick()
        assertEquals(ModuleRequest("Infantry_Cli", enable = false, cascade = true), asked.last())
        // Both rows now offer Enable: the change is in modules.json, waiting for a restart.
        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Enable")
        onNodeWithTag(MODULE_TOGGLE_TAG_PREFIX + "Acme_Hello").assertTextEquals("Enable")
    }

    // Ticket cb1ceb93: each module is a card: name, version, tier, author and license, state, dependencies, reason.
    @Test
    fun `a card names the tier, author, license and state in plain words`() = runDesktopComposeUiTest(900, 600) {
        val refused = ModuleStatus("Acme_Broken", Tier.COMMUNITY, ModuleState.REFUSED, reason = "needs Infantry_Git, which is off", manifest = ModuleManifest("Acme_Broken", "0.2.0", author = "Acme", license = "MIT"))
        setContent { ModulesSection(TabFactory(emptyList(), statuses = statuses + refused)) }
        onNodeWithTag(MODULE_TIER_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Core")
        onNodeWithTag(MODULE_TIER_TAG_PREFIX + "Acme_Hello").assertTextEquals("Community")
        onNodeWithTag(MODULE_STATE_TAG_PREFIX + "Infantry_Cli").assertTextEquals("Loaded")
        onNodeWithTag(MODULE_STATE_TAG_PREFIX + "Acme_Broken").assertTextEquals("Refused")
        onNodeWithTag(MODULE_BYLINE_TAG_PREFIX + "Acme_Hello").assertTextEquals("By Acme · License NOASSERTION")
        onNodeWithTag(MODULE_REASON_TAG_PREFIX + "Acme_Broken").assertTextEquals("needs Infantry_Git, which is off")
        // A core manifest with no author or license draws no empty byline.
        onNodeWithTag(MODULE_BYLINE_TAG_PREFIX + "Infantry_Cli").assertDoesNotExist()
    }

    @Test
    fun `cards sit side by side when there is room and stack when there is not`() {
        runDesktopComposeUiTest(900, 600) {
            setContent { ModulesSection(TabFactory(emptyList(), statuses = statuses)) }
            val a = onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_Cli").getBoundsInRoot()
            val b = onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Acme_Hello").getBoundsInRoot()
            assertEquals(a.top, b.top, "same row")
            assertTrue(b.left > a.right, "side by side")
        }
        runDesktopComposeUiTest(320, 600) {
            setContent { ModulesSection(TabFactory(emptyList(), statuses = statuses)) }
            val a = onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_Cli").getBoundsInRoot()
            val b = onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Acme_Hello").getBoundsInRoot()
            assertTrue(b.top >= a.bottom, "stacked")
        }
    }

    @Test
    fun `no modules says so instead of drawing nothing`() = runDesktopComposeUiTest(900, 600) {
        setContent { ModulesSection(TabFactory(emptyList(), statuses = emptyList())) }
        onNodeWithTag(MODULE_EMPTY_TAG).assertExists()
        assertTrue(onAllNodesWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_Cli").fetchSemanticsNodes().isEmpty())
    }
}
