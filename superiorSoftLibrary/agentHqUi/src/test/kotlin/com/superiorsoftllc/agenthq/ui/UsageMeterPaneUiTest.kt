package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.superiorsoftllc.agenthq.plugins.official.stats.UsageMeterBody
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterPrice
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterReport
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRequest
import com.superiorsoftllc.agenthq.ui.protocol.UsageMeterRow
import kotlin.test.Test

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
}
