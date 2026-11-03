package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.infantry.plugins.official.stats.UsageMeterBody
import com.superiorsoftllc.infantry.plugins.official.stats.UsageMeterExtras
import androidx.compose.foundation.background
import com.superiorsoftllc.infantry.ui.protocol.UsageMeterPrice
import com.superiorsoftllc.infantry.ui.protocol.UsageMeterReport
import com.superiorsoftllc.infantry.ui.protocol.UsageMeterRequest
import com.superiorsoftllc.infantry.ui.protocol.UsageMeterRow
import kotlin.test.Test
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import com.superiorsoftllc.infantry.plugins.official.stats.USAGE_TOKENS_GROUP_TAG
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import com.superiorsoftllc.infantry.ui.model.ThemeSettings

/** STATS-ACC-1: the usage meter pane states the turn definition, shows only recorded data, and answers n/a / no data yet honestly. */
@OptIn(ExperimentalTestApi::class)
class UsageMeterPaneUiTest {
    @Test fun `an empty report says no data yet and defines a turn`() = runComposeUiTest {
        setContent { UsageMeterBody(UsageMeterReport(keepWeeks = 4)) }
        onNodeWithTag("stats-turn-definition").assertExists()
        onNodeWithTag("stats-compare").assertExists()
        onAllNodesWithText("No data yet.", useUnmergedTree = true).assertCountEquals(3)
    }

    @Test fun `a recorded harness shows its line with n-a where the harness reported nothing`() = runComposeUiTest {
        val rep = UsageMeterReport(
            rows = listOf(UsageMeterRow("2026-10-29", "pm", "pi", "x/y", "pm", 1, 4, 400, 10, 0, 0, 400, null)),
            requests = listOf(UsageMeterRequest(1, "sess1234", null, "pm", "pi", "x/y", "pm", 100, 5, null, null, 100, null)),
            prices = listOf(UsageMeterPrice("sonnet", 3.0, 15.0, 0.3, 3.75)),
            keepWeeks = 4,
        )
        setContent { UsageMeterBody(rep) }
        onNodeWithText("hit n/a", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithTag("stats-context").assertExists()
    }

    /** CHART-STYLE-1: renders the cost card in both themes to build/chart-style/<tag>-<theme>.png (tag from -Dchart.shot). */
    @Test fun `cost card renders in both themes`() = runComposeUiTest {
        val rep = UsageMeterReport(
            requests = listOf(
                UsageMeterRequest(1, "sess1234abcd", "tick1111", "frontend", "claude", "claude-sonnet-5-5", "pm", 1_000_000, 200_000, 0, 0, 1, null),
                UsageMeterRequest(2, "sess5678abcd", "tick2222", "pm", "pi", "x/mystery", "pm", 100, 5, null, null, 1, null),
                UsageMeterRequest(3, "sess1234abcd", "tick1111", "frontend", "claude", "claude-sonnet-5-5", "pm", 500_000, 90_000, 0, 0, 1, null),
            ),
            prices = listOf(UsageMeterPrice("sonnet", 3.0, 15.0, 0.3, 3.75)), keepWeeks = 4,
        )
        val tag = System.getProperty("chart.shot") ?: "after"
        for (dark in listOf(true, false)) {
            Palette.theme = ThemeSettings(dark = dark)
            setContent { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(560.dp).background(Palette.Bg).padding(8.dp).testTag("shot")) { androidx.compose.foundation.layout.Column { UsageMeterExtras(rep) } } }
            val dir = java.io.File("build/chart-style").also { it.mkdirs() }
            javax.imageio.ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", java.io.File(dir, "$tag-${if (dark) "dark" else "light"}.png"))
        }
        Palette.theme = ThemeSettings()
    }

    private val twoDays = UsageMeterReport(
        rows = listOf(
            UsageMeterRow("2026-10-28", "worker", "claude", "m", "worker", 1, 5, 100, 10, 300, 0, 410, 1.0),
            UsageMeterRow("2026-10-29", "pm", "claude", "m", "pm", 1, 7, 100, 10, 100, 0, 210, 1.0),
        ),
        keepWeeks = 4,
    )

    /** USAGE-UI-1: the toggle is a labelled segmented control, selection is exposed, and the group right after it holds the toggle-driven data. */
    @Test fun `toggle is labelled and selectable and drives one group`() = runComposeUiTest {
        setContent { UsageMeterBody(twoDays) }
        onNodeWithText("Period").assertExists()
        onNodeWithText("Group by").assertExists()
        onNodeWithText("By day").assertIsSelected()
        onNodeWithText("By week").assertIsNotSelected()
        val inGroup = hasAnyAncestor(hasTestTag(USAGE_TOKENS_GROUP_TAG))
        onNode(hasText("TURNS (MODEL REQUESTS) PER DAY") and inGroup).assertExists()
        onAllNodes(hasText("2026-10-28") and inGroup, useUnmergedTree = true)[0].assertExists()
        onNode(hasText("HARNESS COMPARISON", substring = true) and inGroup).assertDoesNotExist()
        onNode(hasTestTag("stats-compare") and inGroup).assertDoesNotExist()
        onNodeWithText("By week").performClick()
        onNodeWithText("By week").assertIsSelected()
        onNode(hasText("TURNS (MODEL REQUESTS) PER WEEK") and inGroup).assertExists()
        onAllNodes(hasText("week of 2026-10-26") and inGroup, useUnmergedTree = true)[0].assertExists()
    }

    /** USAGE-UI-1: every colour the tab draws text with reaches 4.5:1 on the surface it sits on, in both themes. */
    @Test fun `tab text colours are readable on their surfaces in both themes`() {
        val saved = Palette.theme
        try {
            for (dark in listOf(true, false)) {
                Palette.theme = ThemeSettings(dark = dark)
                // Accent is never used as text colour in this tab (3.8:1 on the light theme); only as fill for bars and the selected segment.
                for (surface in listOf(Palette.Raised, Palette.Panel)) {
                    val tokens = listOf("Text" to Palette.Text, "Muted" to Palette.Muted)
                    for ((n, c) in tokens) {
                        val r = Contrast.ratio(c, surface)
                        check(r >= Contrast.TEXT_AA) { "$n on surface is $r in ${if (dark) "dark" else "light"}" }
                    }
                }
                check(Contrast.ratio(Contrast.onColor(Palette.Accent), Palette.Accent) >= Contrast.TEXT_AA) { "selected segment text unreadable" }
            }
        } finally { Palette.theme = saved }
    }

    /** USAGE-UI-1: screenshots of both toggle states to build/usage-ui/<tag>-<state>.png. */
    @Test fun `screenshots of both toggle states`() = runComposeUiTest {
        val tag = System.getProperty("chart.shot") ?: "after"
        setContent { androidx.compose.foundation.layout.Box(Modifier.width(900.dp).height(700.dp).background(Palette.Bg).testTag("shot")) { UsageMeterBody(twoDays) } }
        val dir = java.io.File("build/usage-ui").also { it.mkdirs() }
        javax.imageio.ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", java.io.File(dir, "$tag-day.png"))
        onNodeWithText("By week").performClick()
        waitForIdle()
        javax.imageio.ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", java.io.File(dir, "$tag-week.png"))
    }
}
