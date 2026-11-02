package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.PanelDeclaration
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.ModuleState
import com.superiorsoftllc.agenthq.plugin.host.ModuleStatus
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_BACK_TAG
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_CRUMB_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_EDIT_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_ROW_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.MODULE_USED_BY_TAG_PREFIX
import com.superiorsoftllc.agenthq.ui.plugin.ModulesSection
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory
import kotlin.test.Test

/** PLUGIN-TREE-UI-1: the Plugins tab shows direct children only; Edit steps in, the breadcrumb steps out. */
@OptIn(ExperimentalTestApi::class)
class PluginTreeUiTest {
    private fun s(m: ModuleManifest) = ModuleStatus(m.name, Tier.CORE, ModuleState.LOADED, manifest = m)
    private val chat = ModuleManifest("Infantry_Chat", "1.0.0", slots = listOf("chat.feed"))
    private val feed = ModuleManifest("Infantry_ChatFeed", "1.0.0", panels = listOf(PanelDeclaration("infantry.chatfeed", "chat.feed")), slots = listOf("feed.row"))
    private val row = ModuleManifest("Test_Row", "1.0.0", parents = listOf("Infantry_ChatFeed"))

    @Test fun editStepsIntoAChildAndTheBreadcrumbStepsOut() = runDesktopComposeUiTest(900, 700) {
        setContent { ModulesSection(TabFactory(emptyList(), statuses = listOf(chat, feed, row).map(::s))) }

        // Top level: the parent only; its child and grandchild are not cards here.
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_Chat").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_ChatFeed").assertDoesNotExist()
        onNodeWithTag(MODULE_EDIT_TAG_PREFIX + "Infantry_Chat").performClick()

        // Stepped in: breadcrumb, the direct child (used by its parent), not the grandchild.
        onNodeWithTag(MODULE_CRUMB_TAG_PREFIX + "Infantry_Chat").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_ChatFeed").assertExists()
        onNodeWithTag(MODULE_USED_BY_TAG_PREFIX + "Infantry_ChatFeed").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Test_Row").assertDoesNotExist()
        onNodeWithTag(MODULE_EDIT_TAG_PREFIX + "Infantry_ChatFeed").performClick()

        // Same view one level down: the grandchild is now the direct child.
        onNodeWithTag(MODULE_CRUMB_TAG_PREFIX + "Infantry_ChatFeed").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Test_Row").assertExists()

        onNodeWithTag(MODULE_BACK_TAG).performClick()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_ChatFeed").assertExists()
        onNodeWithTag(MODULE_CRUMB_TAG_PREFIX + "root").performClick()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_Chat").assertExists()
        onNodeWithTag(MODULE_ROW_TAG_PREFIX + "Infantry_ChatFeed").assertDoesNotExist()
    }
}
