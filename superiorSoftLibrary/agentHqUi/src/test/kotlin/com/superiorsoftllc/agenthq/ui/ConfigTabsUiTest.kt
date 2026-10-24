package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.UiFlags
import kotlin.test.Test
import kotlin.test.assertEquals

/** CONFIG-TABS-1: the configuration modal is tabbed: a tab shows only its sections, empty tabs are not offered. */
@OptIn(ExperimentalTestApi::class)
class ConfigTabsUiTest {
    private fun section(e: ConfigEntry) = CONFIG_SECTION_TAG_PREFIX + e.name
    private fun tab(t: ConfigTab) = CONFIG_TAB_TAG_PREFIX + t.name

    @Test fun `it opens on Appearance and a tab shows only its own sections`() = runDesktopComposeUiTest(1000, 900) {
        val picked = mutableListOf<ConfigTab>()
        setContent {
            ConfigDialog(
                UiFlags(), onFlags = {}, onDismiss = {},
                logs = LogsFolder("C:/AgentHQ/logs") { true },
                onTab = { picked += it },
            )
        }
        onNodeWithTag(tab(ConfigTab.APPEARANCE)).assertIsSelected()
        onNodeWithTag(section(ConfigEntry.BLUR)).assertExists()
        onNodeWithTag(section(ConfigEntry.AGENT_SCOPE)).assertDoesNotExist()
        onNodeWithTag(section(ConfigEntry.LOGS)).assertDoesNotExist()

        onNodeWithTag(tab(ConfigTab.GENERAL)).performClick()
        onNodeWithTag(tab(ConfigTab.GENERAL)).assertIsSelected()
        onNodeWithTag(section(ConfigEntry.AGENT_SCOPE)).assertExists()
        onNodeWithTag(section(ConfigEntry.PROGRESS_STALE)).assertExists()
        onNodeWithTag(section(ConfigEntry.BLUR)).assertDoesNotExist()

        onNodeWithTag(tab(ConfigTab.ABOUT)).performClick()
        onNodeWithTag(section(ConfigEntry.LOGS)).assertExists()
        onNodeWithTag(section(ConfigEntry.AGENT_SCOPE)).assertDoesNotExist()
        assertEquals(listOf(ConfigTab.GENERAL, ConfigTab.ABOUT), picked, "the host is told each pick, so it can reopen there")
    }

    @Test fun `a tab with nothing to show is not offered`() = runDesktopComposeUiTest(1000, 900) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}) }
        onNodeWithTag(tab(ConfigTab.GENERAL)).assertExists()
        onNodeWithTag(tab(ConfigTab.SECRETS)).assertDoesNotExist()
        onNodeWithTag(tab(ConfigTab.DEVICES)).assertDoesNotExist()
        onNodeWithTag(tab(ConfigTab.ABOUT)).assertDoesNotExist()
    }

    @Test fun `it reopens on the tab the host remembered`() = runDesktopComposeUiTest(1000, 900) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, initialTab = ConfigTab.NOTIFICATIONS) }
        onNodeWithTag(tab(ConfigTab.NOTIFICATIONS)).assertIsSelected()
        onNodeWithTag(section(ConfigEntry.VOICE)).assertExists()
    }
}
