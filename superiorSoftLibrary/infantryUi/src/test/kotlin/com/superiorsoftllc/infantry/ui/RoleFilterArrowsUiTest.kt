package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.agents.*

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertTrue

/** UI-ARROWS-1: the sidebar's role filter chips get the tab strips' scroll arrows when they overflow. */
@OptIn(ExperimentalTestApi::class)
class RoleFilterArrowsUiTest {
    private val roles = listOf("assistant", "backend", "designer", "frontend", "pm", "qa", "reviewer", "writer")
    private fun stateOf(roles: List<String>) = AppState(
        agents = roles.mapIndexed { i, r ->
            AgentView(SessionRecord(id = "a$i", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE), role = r)
        }.associateBy { it.id },
    )

    private fun ComposeUiTest.chips(roles: List<String>, widthDp: Int) {
        setContent { Box(Modifier.width(widthDp.dp)) { RoleFilterChips(stateOf(roles), null) {} } }
        waitForIdle()
    }

    /** Whether [tag]'s chip lies wholly inside the chip row's visible width. */
    private fun ComposeUiTest.inView(tag: String): Boolean {
        val row = onNodeWithTag(ROLE_FILTER_ROW_TAG).getUnclippedBoundsInRoot()
        val chip = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        return chip.left >= row.left - 0.5.dp && chip.right <= row.right + 0.5.dp
    }

    @Test
    fun manyChipsInANarrowRowShowBothArrowsWithOnlyTheRightEnabled() = runDesktopComposeUiTest(400, 200) {
        chips(roles, 200)
        onNodeWithTag(ROLE_FILTER_ARROW_LEFT_TAG).assertIsNotEnabled()
        onNodeWithTag(ROLE_FILTER_ARROW_RIGHT_TAG).assertIsEnabled()
        assertTrue(!inView(ROLE_FILTER_CHIP_TAG_PREFIX + "writer"), "the last chip starts off the row")
    }

    @Test
    fun clickingRightScrollsUntilTheLastChipIsVisible() = runDesktopComposeUiTest(400, 200) {
        chips(roles, 200)
        val first = onNodeWithTag(ROLE_FILTER_CHIP_TAG_PREFIX + "all", useUnmergedTree = true).getUnclippedBoundsInRoot().left
        onNodeWithTag(ROLE_FILTER_ARROW_RIGHT_TAG).performClick()
        waitForIdle()
        val moved = first - onNodeWithTag(ROLE_FILTER_CHIP_TAG_PREFIX + "all", useUnmergedTree = true).getUnclippedBoundsInRoot().left
        // One press moves about one chip, not a whole page.
        assertTrue(moved > 40.dp && moved <= 80.dp, "one press moved $moved")
        onNodeWithTag(ROLE_FILTER_ARROW_LEFT_TAG).assertIsEnabled()
        repeat(20) { onNodeWithTag(ROLE_FILTER_ARROW_RIGHT_TAG).performClick(); waitForIdle() }
        onNodeWithTag(ROLE_FILTER_ARROW_RIGHT_TAG).assertIsNotEnabled()
        assertTrue(inView(ROLE_FILTER_CHIP_TAG_PREFIX + "writer"), "the last chip is in view at the end")
    }

    @Test
    fun theArrowsAreKeyboardButtons() = runDesktopComposeUiTest(400, 200) {
        chips(roles, 200)
        onNodeWithTag(ROLE_FILTER_ARROW_RIGHT_TAG).requestFocus().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        onNodeWithTag(ROLE_FILTER_ARROW_LEFT_TAG).assertIsEnabled()
    }

    @Test
    fun aVerticalMouseWheelScrollsTheChipsSideways() = runDesktopComposeUiTest(400, 200) {
        chips(roles, 200)
        onNodeWithTag(ROLE_FILTER_ROW_TAG).performMouseInput { moveTo(center); scroll(3f) }
        waitForIdle()
        onNodeWithTag(ROLE_FILTER_ARROW_LEFT_TAG).assertIsEnabled()
    }

    @Test
    fun whenEveryChipFitsThereAreNoArrows() = runDesktopComposeUiTest(800, 200) {
        chips(listOf("pm", "qa"), 600)
        assertTrue(onAllNodesWithTag(ROLE_FILTER_ARROW_LEFT_TAG).fetchSemanticsNodes().isEmpty(), "no left arrow")
        assertTrue(onAllNodesWithTag(ROLE_FILTER_ARROW_RIGHT_TAG).fetchSemanticsNodes().isEmpty(), "no right arrow")
        assertTrue(inView(ROLE_FILTER_CHIP_TAG_PREFIX + "qa"), "every chip is in view")
    }
}
