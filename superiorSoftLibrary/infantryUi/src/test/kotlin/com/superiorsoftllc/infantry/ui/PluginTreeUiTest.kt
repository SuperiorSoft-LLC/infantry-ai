// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.PanelDeclaration
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleState
import com.superiorsoftllc.infantry.plugin.host.ModuleStatus
import com.superiorsoftllc.infantry.ui.plugin.MODULE_BACK_TAG
import com.superiorsoftllc.infantry.ui.plugin.MODULE_CRUMB_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_EDIT_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_ROW_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.MODULE_USED_BY_TAG_PREFIX
import com.superiorsoftllc.infantry.ui.plugin.ModulesSection
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import com.superiorsoftllc.infantry.ui.plugin.MODULE_CHILD_TAG_PREFIX
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/** PLUGIN-TREE-UI-1: the Plugins tab shows direct children only; Edit steps in, the breadcrumb steps out. */
@OptIn(ExperimentalTestApi::class)
class PluginTreeUiTest {
    private fun s(m: ModuleManifest) = ModuleStatus(m.name, Tier.CORE, ModuleState.LOADED, manifest = m)
    private val chat = ModuleManifest("Infantry_Chat", "1.0.0", slots = listOf("chat.feed"))
    private val feed = ModuleManifest("Infantry_ChatFeed", "1.0.0", panels = listOf(PanelDeclaration("infantry.chatfeed", "chat.feed")), slots = listOf("feed.row"))
    private val row = ModuleManifest("Test_Row", "1.0.0", parents = listOf("Infantry_ChatFeed"))

    @Test fun editStepsIntoAChildAndTheBreadcrumbStepsOut() = runDesktopComposeUiTest(900, 700) {
        // Scrolled well below the top, like the Plugins section inside the settings dialog.
        setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(1500.dp))
                ModulesSection(TabFactory(emptyList(), statuses = listOf(chat, feed, row).map(::s)))
            }
        }

        // Top level: the parent only; its child and grandchild are not cards here.
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_Chat").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_ChatFeed").assertDoesNotExist()
        onNodeWithTag(MODULE_CHILD_TAG_PREFIX + "Infantry_ChatFeed").performScrollTo().assertIsDisplayed()
        onNodeWithTag(MODULE_EDIT_TAG_PREFIX + "Infantry_Chat").performScrollTo().performClick()
        onNodeWithTag(MODULE_CRUMB_TAG_PREFIX + "Infantry_Chat").assertIsDisplayed()

        // Stepped in: breadcrumb, the direct child (used by its parent), not the grandchild.
        onNodeWithTag(MODULE_CRUMB_TAG_PREFIX + "Infantry_Chat").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_ChatFeed").assertExists()
        onNodeWithTag(MODULE_USED_BY_TAG_PREFIX + "Infantry_ChatFeed").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Test_Row").assertDoesNotExist()
        onNodeWithTag(MODULE_EDIT_TAG_PREFIX + "Infantry_ChatFeed").performScrollTo().performClick()

        // Same view one level down: the grandchild is now the direct child.
        onNodeWithTag(MODULE_CRUMB_TAG_PREFIX + "Infantry_ChatFeed").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Test_Row").assertExists()

        onNodeWithTag(MODULE_BACK_TAG).performClick()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_ChatFeed").assertExists()
        onNodeWithTag(MODULE_CRUMB_TAG_PREFIX + "root").performClick()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_Chat").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_ChatFeed").assertDoesNotExist()
    }

    // PLUGIN-TREE-2b: a child shared by two parents.
    private val pa = ModuleManifest("Test_A", "1.0.0")
    private val pb = ModuleManifest("Test_B", "1.0.0")
    private val shared = ModuleManifest("Test_Shared", "1.0.0", parents = listOf("Test_A", "Test_B"))

    @Test fun sharedChildShowsInUseByAndAsksBeforeSwitchingOff() = runDesktopComposeUiTest(900, 700) {
        val asked = mutableListOf<com.superiorsoftllc.infantry.ui.plugin.ModuleRequest>()
        val changes = com.superiorsoftllc.infantry.ui.plugin.ModuleChanges { r ->
            asked += r; com.superiorsoftllc.infantry.plugin.host.ModuleChange.Applied(com.superiorsoftllc.infantry.plugin.api.ModulesConfig(), listOf(r.module), "ok")
        }
        setContent { ModulesSection(TabFactory(emptyList(), statuses = listOf(pa, pb, shared).map(::s), changes = changes)) }
        onNodeWithTag(MODULE_EDIT_TAG_PREFIX + "Test_A").performClick()
        onNodeWithTag(com.superiorsoftllc.infantry.ui.plugin.MODULE_IN_USE_BY_TAG_PREFIX + "Test_Shared").assertExists()
        onNodeWithTag(com.superiorsoftllc.infantry.ui.plugin.MODULE_TOGGLE_TAG_PREFIX + "Test_Shared").performClick()
        // A confirm, not a refusal; nothing written yet.
        onNodeWithTag(com.superiorsoftllc.infantry.ui.plugin.MODULE_SHARED_CONFIRM_TAG_PREFIX + "Test_Shared").assertExists()
        kotlin.test.assertTrue(asked.isEmpty())
        onNodeWithTag(com.superiorsoftllc.infantry.ui.plugin.MODULE_SHARED_NO_TAG_PREFIX + "Test_Shared").performClick()
        kotlin.test.assertTrue(asked.isEmpty())
        onNodeWithTag(com.superiorsoftllc.infantry.ui.plugin.MODULE_TOGGLE_TAG_PREFIX + "Test_Shared").performClick()
        onNodeWithTag(com.superiorsoftllc.infantry.ui.plugin.MODULE_SHARED_YES_TAG_PREFIX + "Test_Shared").performClick()
        kotlin.test.assertEquals(listOf("Test_Shared" to false), asked.map { it.module to it.enable })
    }

    @Test fun heldOffChildShowsTheChipAndKeepsItsOwnSwitchOn() = runDesktopComposeUiTest(900, 700) {
        val held = ModuleStatus("Test_Shared", Tier.CORE, ModuleState.DISABLED, "all its parents are off", shared, heldOffBy = listOf("Test_A", "Test_B"))
        val off = listOf(pa, pb).map { ModuleStatus(it.name, Tier.CORE, ModuleState.DISABLED, manifest = it) }
        val changes = com.superiorsoftllc.infantry.ui.plugin.ModuleChanges { com.superiorsoftllc.infantry.plugin.host.ModuleChange.Applied(com.superiorsoftllc.infantry.plugin.api.ModulesConfig(), emptyList(), "ok") }
        setContent { ModulesSection(TabFactory(emptyList(), statuses = off + held, changes = changes)) }
        onNodeWithTag(MODULE_EDIT_TAG_PREFIX + "Test_A").performClick()
        onNodeWithTag(com.superiorsoftllc.infantry.ui.plugin.MODULE_HELD_OFF_TAG_PREFIX + "Test_Shared").assertExists()
        // Own flag on: the button offers Disable, not Enable.
        onNodeWithTag(com.superiorsoftllc.infantry.ui.plugin.MODULE_TOGGLE_TAG_PREFIX + "Test_Shared").assertTextEquals("Disable").assertExists()
    }
}
