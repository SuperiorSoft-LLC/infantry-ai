// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.plugins.official.processes.PROCESSES_BADGE_TAG
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BLOCKS-LAYOUT L2: the status bar is one ordered array of blocks. Left to right: status text, processes badge, the
 * flexible gap, the shortcut hint, the build stamp, the cog. The hint hides at compact width; badge and stamp stay.
 */
@OptIn(ExperimentalTestApi::class)
class StatusBarBlocksUiTest {
    private val ready = Envelope(Envelope.READY, "contract=1.132.0 provider=stub instance=i1")
    private val processes = Envelope(Envelope.PROCESSES, """{"report":{"freeMb":8192,"totalMb":16384}}""")

    private fun withWindow(width: Int, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(width, 800) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", "1.0.0+test") }
                link.push(ready)
                link.push(processes)
                waitUntil(timeoutMillis = 5_000) { onAllNodes(hasTestTag(PROCESSES_BADGE_TAG)).fetchSemanticsNodes().size == 1 }
                body()
            }
        } finally { scope.cancel() }
    }

    @Test fun `the bar's items sit in one order and the hint shows when wide`() = withWindow(1400) {
        waitForIdle()
        val status = onNode(hasText("daemon stub", substring = true)).getUnclippedBoundsInRoot()
        val badge = onNodeWithTag(PROCESSES_BADGE_TAG).getUnclippedBoundsInRoot()
        val hint = onNode(hasText("^K search", substring = true)).getUnclippedBoundsInRoot()
        val stamp = onNodeWithTag(FOOTER_BUILD_TAG).getUnclippedBoundsInRoot()
        val cog = onNode(hasContentDescription("Configuration")).getUnclippedBoundsInRoot()
        val lefts = listOf(status.left, badge.left, hint.left, stamp.left, cog.left)
        assertEquals(lefts.sorted(), lefts, "status, badge, hint, stamp, cog must run left to right: $lefts")
        assertTrue(badge.left >= status.right && hint.left >= badge.right && stamp.left >= hint.right && cog.left >= stamp.right)
    }

    @Test fun `the hint and build stamp hide at compact width while badge and cog still render`() = withWindow(400) {
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("^K search", substring = true)).fetchSemanticsNodes().isEmpty() }
        onNodeWithTag(PROCESSES_BADGE_TAG).assertExists()
        onNodeWithTag(FOOTER_BUILD_TAG).assertDoesNotExist()
        onNode(hasContentDescription("Configuration")).assertExists()
    }

    @Test fun `the config cog is displayed at 360 dp`() = withWindow(360) {
        waitForIdle()
        onNode(hasContentDescription("Configuration")).assertIsDisplayed()
        val cog = onNode(hasContentDescription("Configuration")).getUnclippedBoundsInRoot()
        assertTrue(cog.right <= 360.dp, "cog must sit inside the 360 dp window: ${cog.right}")
    }
}
