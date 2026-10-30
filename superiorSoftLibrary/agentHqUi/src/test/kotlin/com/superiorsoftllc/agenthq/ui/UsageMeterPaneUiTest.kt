package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.agenthq.plugins.official.stats.UsageMeterBody
import com.superiorsoftllc.agenthq.plugins.official.stats.UsageMeterExtras
import androidx.compose.foundation.background
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterPrice
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterReport
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRequest
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRow
import kotlin.test.Test
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import com.superiorsoftllc.agenthq.ui.model.ThemeSettings

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
}
