package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.plugins.official.processes.PROCESSES_BADGE_TAG
import com.superiorsoftllc.agenthq.plugins.official.processes.PROCESSES_PANEL_TAG
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue

/**
 * BLOCKS-SHELL-3: the processes badge moved out of the static StatusBar into the processesBadge child block (leading
 * slot). It must still render after the status text and before the flexible gap (so before the shortcut hint), be absent
 * with no report, and still open the processes panel (asking the daemon for a fresh report) on a tap.
 */
@OptIn(ExperimentalTestApi::class)
class StatusProcessesBadgeUiTest {
    private val ready = Envelope(Envelope.READY, "contract=1.132.0 provider=stub instance=i1")
    private val processes = Envelope(Envelope.PROCESSES, """{"report":{"freeMb":8192,"totalMb":16384}}""")

    private fun withWindow(body: androidx.compose.ui.test.ComposeUiTest.(ChangesFakeLink) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1400, 800) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null) }
                link.push(ready)
                body(link)
            }
        } finally { scope.cancel() }
    }

    @Test fun `the badge shows after the status text and before the shortcut hint, opens the panel, and is absent without a report`() = withWindow { link ->
        waitForIdle()
        onNodeWithTag(PROCESSES_BADGE_TAG).assertDoesNotExist()

        link.push(processes)
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTagCount(PROCESSES_BADGE_TAG) == 1 }
        onNodeWithTag(PROCESSES_BADGE_TAG).assertIsDisplayed()

        val badge = onNodeWithTag(PROCESSES_BADGE_TAG).getUnclippedBoundsInRoot()
        val status = onNode(hasText("daemon stub", substring = true)).getUnclippedBoundsInRoot()
        val hint = onNode(hasText("^K search", substring = true)).getUnclippedBoundsInRoot()
        assertTrue(badge.left >= status.right, "badge $badge must start at or after the status text $status")
        assertTrue(badge.right <= hint.left, "badge $badge must end before the shortcut hint $hint")
        assertTrue(hint.left - badge.right > 0.dp, "the flexible gap sits between the badge and the hint")

        link.sent.clear()
        // The App still dials the dead ws url for real; when that failure lands it flips the connection to not-live, which drops
        // the badge and any open panel. So: re-announce the daemon when the badge is gone, and re-click until the panel exists.
        var attempts = 0
        waitUntil(timeoutMillis = 10_000) {
            if (onAllNodesWithTagCount(PROCESSES_PANEL_TAG) >= 1) true
            else {
                if (onAllNodesWithTagCount(PROCESSES_BADGE_TAG) == 0) {
                    link.push(ready)
                    link.push(processes)
                } else if (attempts++ % 10 == 0) {
                    onNodeWithTag(PROCESSES_BADGE_TAG).performClick()
                    waitForIdle()
                }
                false
            }
        }
        waitUntil(timeoutMillis = 5_000) { link.sentOf(Envelope.PROCESSES).isNotEmpty() }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
