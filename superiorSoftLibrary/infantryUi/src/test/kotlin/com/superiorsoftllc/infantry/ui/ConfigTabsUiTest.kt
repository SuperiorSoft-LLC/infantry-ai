package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.UiFlags
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
                logs = LogsFolder("C:/Infantry/logs") { true },
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

    // CONFIG-SEARCH-1 (ticket 3463d1b2): search, then jump to the section and outline it.
    @Test fun `searching lists matching sections and picking one opens its tab, outlined`() = runDesktopComposeUiTest(1000, 900) {
        val picked = mutableListOf<ConfigTab>()
        setContent {
            ConfigDialog(
                UiFlags(), onFlags = {}, onDismiss = {},
                logs = LogsFolder("C:/Infantry/logs") { true },
                onTab = { picked += it },
            )
        }
        mainClock.autoAdvance = false
        onNodeWithTag(CONFIG_SEARCH_TAG).performTextInput("diagnostics")
        mainClock.advanceTimeByFrame()
        onNodeWithTag(CONFIG_RESULT_TAG_PREFIX + ConfigEntry.LOGS.name).assertExists()
        onNodeWithTag(CONFIG_RESULT_TAG_PREFIX + ConfigEntry.BLUR.name).assertDoesNotExist()
        onNodeWithTag(section(ConfigEntry.BLUR)).assertDoesNotExist()

        onNodeWithTag(CONFIG_RESULT_TAG_PREFIX + ConfigEntry.LOGS.name).performClick()
        mainClock.advanceTimeBy(100)
        onNodeWithTag(tab(ConfigTab.ABOUT)).assertIsSelected()
        onNodeWithTag(section(ConfigEntry.LOGS)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, CONFIG_HIGHLIGHT_STATE))
        onNodeWithTag(CONFIG_SEARCH_RESULTS_TAG).assertDoesNotExist()
        assertEquals(listOf(ConfigTab.ABOUT), picked)

        mainClock.advanceTimeBy(CONFIG_HIGHLIGHT_MS + 200)
        onNodeWithTag(section(ConfigEntry.LOGS)).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test fun `a search with no match says so`() = runDesktopComposeUiTest(1000, 900) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}) }
        onNodeWithTag(CONFIG_SEARCH_TAG).performTextInput("zzqx")
        onNodeWithTag(CONFIG_SEARCH_EMPTY_TAG).assertExists()
    }
}
