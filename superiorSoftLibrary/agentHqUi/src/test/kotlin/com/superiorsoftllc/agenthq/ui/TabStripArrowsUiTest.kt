package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.chat.*
import com.superiorsoftllc.agenthq.plugins.official.chatfeed.*

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AppState
import kotlin.test.Test
import kotlin.test.assertTrue

/** PLUGINS-3: left and right arrows on every tab strip show that it scrolls, and scroll it. */
@OptIn(ExperimentalTestApi::class)
class TabStripArrowsUiTest {
    @Test
    fun whileTabsOverflowBothArrowsShowAndOnlyTheOneWithRoomIsEnabled() = runDesktopComposeUiTest(390, 200) {
        setContent { CenterTabBar(AppState(), "infantry.cli") {} }
        waitForIdle()
        onNodeWithTag(TAB_BAR_ARROW_LEFT_TAG).assertIsNotEnabled()
        onNodeWithTag(TAB_BAR_ARROW_RIGHT_TAG).assertIsEnabled()
    }

    @Test
    fun theRightArrowScrollsTheStripSoTheLeftOneEnables() = runDesktopComposeUiTest(390, 200) {
        setContent { CenterTabBar(AppState(), "infantry.cli") {} }
        waitForIdle()
        onNodeWithTag(TAB_BAR_ARROW_RIGHT_TAG).performClick()
        waitForIdle()
        onNodeWithTag(TAB_BAR_ARROW_LEFT_TAG).assertIsEnabled()
    }

    @Test
    fun scrollingToTheEndDisablesTheRightArrow() = runDesktopComposeUiTest(390, 200) {
        setContent { CenterTabBar(AppState(), "infantry.cli") {} }
        waitForIdle()
        repeat(20) { onNodeWithTag(TAB_BAR_ARROW_RIGHT_TAG).performClick(); waitForIdle() }
        onNodeWithTag(TAB_BAR_ARROW_RIGHT_TAG).assertIsNotEnabled()
        onNodeWithTag(TAB_BAR_ARROW_LEFT_TAG).assertIsEnabled()
    }

    @Test
    fun aVerticalMouseWheelScrollsTheStripSideways() = runDesktopComposeUiTest(390, 200) {
        setContent { CenterTabBar(AppState(), "infantry.cli") {} }
        waitForIdle()
        onNodeWithTag(CENTER_TAB_BAR_TAG).performMouseInput { moveTo(center); scroll(3f) }
        waitForIdle()
        onNodeWithTag(TAB_BAR_ARROW_LEFT_TAG).assertIsEnabled()
    }

    @Test
    fun whenEveryTabFitsThereAreNoArrows() = runDesktopComposeUiTest(1600, 200) {
        setContent { CenterTabBar(AppState(), "infantry.cli") {} }
        waitForIdle()
        assertTrue(onAllNodesWithTag(TAB_BAR_ARROW_LEFT_TAG).fetchSemanticsNodes().isEmpty(), "no left arrow")
        assertTrue(onAllNodesWithTag(TAB_BAR_ARROW_RIGHT_TAG).fetchSemanticsNodes().isEmpty(), "no right arrow")
    }
}
