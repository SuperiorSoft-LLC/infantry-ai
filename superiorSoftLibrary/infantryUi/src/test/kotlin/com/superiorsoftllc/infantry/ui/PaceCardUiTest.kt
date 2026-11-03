package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugins.official.pace.ForecastView
import com.superiorsoftllc.infantry.plugins.official.pace.InfantryPace
import com.superiorsoftllc.infantry.plugins.official.pace.PACE_CLIP_TAG
import com.superiorsoftllc.infantry.plugins.official.pace.PACE_EMPTY_TAG
import com.superiorsoftllc.infantry.plugins.official.pace.PACE_TAB_ID
import com.superiorsoftllc.infantry.plugins.official.pace.PACE_TOKENS_TAG
import com.superiorsoftllc.infantry.plugins.official.pace.PACE_TOOLTIP_TAG
import com.superiorsoftllc.infantry.plugins.official.pace.PACE_TREND_TAG
import com.superiorsoftllc.infantry.plugins.official.pace.PaceCard
import com.superiorsoftllc.infantry.plugins.official.pace.PacePoint
import com.superiorsoftllc.infantry.plugins.official.pace.PaceSeries
import com.superiorsoftllc.infantry.plugins.official.pace.PaceTokens
import com.superiorsoftllc.infantry.plugins.official.pace.RateView
import com.superiorsoftllc.infantry.plugins.official.pace.Trend
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PACE-TREND-1: the pace card drawn for real: momentum arrow + word, scale chips, the clip warning, the tooltip, the empty state, and a screenshot in both themes. */
@OptIn(ExperimentalTestApi::class)
class PaceCardUiTest {
    private val h = 3_600_000L
    private val now = 1_761_800_000_000L

    private val points = (0..40).map { i ->
        val used = 20.0 + i * 0.8 + (if (i % 7 == 0) 0.6 else 0.0)
        PacePoint(now - (40 - i) * h / 2, used, 6.0 - i * 0.12, 120.0 - i * 0.45, 0)
    }
    private val series = PaceSeries(
        "claude", "Weekly limit", 10080, now + 60 * h, points.size, points, Trend.FALLING,
        ForecastView(
            true, null, "at the current rate you land at 107%; at the last 6h rate, 96% (falling); at the current rate you cross 100% in 73.0h", 60.0,
            RateView("current rate", 107.0, 73.0), RateView("last 6h rate", 96.0, null),
        ),
    )
    private val tokens = PaceTokens.Totals(1_200_000, 85_000, 9_000_000, 400_000, 310, 12)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.text() =
        fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test fun `the card shows momentum as arrow and word, the tokens as informational, and the tooltip on hover`() = runComposeUiTest {
        setContent { Box(Modifier.width(560.dp).background(Palette.Bg).padding(8.dp)) { PaceCard(series, now, tokens) } }
        assertTrue(onNodeWithTag(PACE_TREND_TAG).text().let { it.contains("Falling") && it.contains("\u25BC") })
        assertTrue(onNodeWithTag(PACE_TOKENS_TAG).text().contains("informational"))
        onNodeWithTag("pace-chart").performMouseInput { moveTo(center); moveTo(center + androidx.compose.ui.geometry.Offset(3f, 0f)); press(); release() }
        waitForIdle()
        assertTrue(onNodeWithTag(PACE_TOOLTIP_TAG).text().contains("pts vs even pace at"), onNodeWithTag(PACE_TOOLTIP_TAG).text())
        onNodeWithTag("pace-metric-used").performClick()
        onNodeWithTag("pace-chart").performMouseInput { moveTo(center); moveTo(center + androidx.compose.ui.geometry.Offset(3f, 0f)); press(); release() }
        waitForIdle()
        assertTrue(onNodeWithTag(PACE_TOOLTIP_TAG).text().contains("% used at"), onNodeWithTag(PACE_TOOLTIP_TAG).text())
    }

    @Test fun `a fixed scale below the data shows the explicit clip warning and Reset returns to Fit`() = runComposeUiTest {
        setContent { Box(Modifier.width(560.dp).background(Palette.Bg).padding(8.dp)) { PaceCard(series, now, null) } }
        onNodeWithTag(PACE_CLIP_TAG).assertDoesNotExist()
        onNodeWithTag("pace-metric-used").performClick()
        onNodeWithTag("pace-scale-50").performClick()
        assertEquals("over scale: line clipped at 50%", onNodeWithTag(PACE_CLIP_TAG).text())
        onNodeWithTag("pace-scale-reset").performClick()
        onNodeWithTag(PACE_CLIP_TAG).assertDoesNotExist()
    }

    @Test fun `under two samples the card says so instead of drawing a flat line`() = runComposeUiTest {
        val one = series.copy(points = points.take(1), trend = Trend.UNKNOWN, forecast = ForecastView(false, "not enough data yet", "not enough data yet", null, null, null))
        setContent { Box(Modifier.width(560.dp).background(Palette.Bg).padding(8.dp)) { PaceCard(one, now, null) } }
        onNodeWithTag(PACE_EMPTY_TAG).assertExists()
    }

    @Test fun `the plugin has no dependency on Stats and its own tab`() {
        assertTrue(InfantryPace.manifest.dependencies.isEmpty(), "the data source is the daemon's meters, not the Stats plugin")
        assertEquals(listOf(PACE_TAB_ID), InfantryPace.manifest.tabs.map { it.id })
    }

    /** Evidence for the report: the card in both themes, build/pace-trend/pace-<theme>.png. */
    @Test fun `the card renders in both themes`() = runComposeUiTest {
        for (dark in listOf(true, false)) {
            Palette.theme = ThemeSettings(dark = dark)
            setContent {
                Box(Modifier.width(620.dp).background(Palette.Bg).padding(8.dp).testTag("shot")) {
                    Column { PaceCard(series, now, tokens) }
                }
            }
            val dir = java.io.File("build/pace-trend").also { it.mkdirs() }
            javax.imageio.ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", java.io.File(dir, "pace-${if (dark) "dark" else "light"}.png"))
        }
        Palette.theme = ThemeSettings()
    }
}
