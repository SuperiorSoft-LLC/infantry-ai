package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MOBILE-TRANSCRIPT-1: on a phone the transcript vanished once the agent went idle. The composer's action row then
 * carried the "Resume with empty box sends ..." hint beside six buttons that already fill a phone's width, so the
 * hint was measured a few pixels wide, wrapped one character per line and grew the row to the whole column: the
 * transcript's weighted box was left 0dp tall and the composer sat right under the tabs.
 *
 * The real window ([App]) at phone width against a fake link, in every session state: the transcript keeps the space
 * between the tabs and the composer, and the composer ends at the bottom of the window.
 */
@OptIn(ExperimentalTestApi::class)
class CompactTranscriptHeightUiTest {
    private val sid = "s-phone-idle"

    private fun withSession(state: String, providerSessionId: String?, width: Int = 400, height: Int = 800, body: ComposeUiTest.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ChangesFakeLink(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(width, height) {
                setContent { App(controller, "ws://127.0.0.1:1/ws", null) }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                val psid = providerSessionId?.let { ""","providerSessionId":"$it"""" } ?: ""
                link.push(
                    Envelope(
                        Envelope.SESSIONS,
                        """[{"id":"$sid","provider":"claude","workingDir":"/w","spawnedAt":1,"lastHeartbeat":1,"state":"$state"$psid}]""",
                    ),
                )
                waitUntil(timeoutMillis = 10_000) { controller.state.value.agents.containsKey(sid) }
                controller.select(sid)
                waitForIdle()
                body()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.assertTranscriptFillsTheColumn(label: String) {
        val transcript = bounds("center-transcript")
        val composer = bounds("composer")
        val window = onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(transcript.height >= 250f, "$label: the transcript keeps the space above the composer (was ${transcript.height}dp)")
        assertTrue(composer.top >= transcript.bottom - 1f, "$label: the composer sits under the transcript")
        assertTrue(composer.height < 260f, "$label: the composer stays compact (was ${composer.height}dp)")
        // Only the status bar (one line) is under the composer.
        assertTrue(window.bottom - composer.bottom < 60f, "$label: the composer is at the bottom (gap ${window.bottom - composer.bottom}dp)")
    }

    @Test
    fun `an idle resumable session keeps its transcript at phone width`() =
        withSession("IDLE", providerSessionId = "p-1") { assertTranscriptFillsTheColumn("idle") }

    @Test
    fun `a busy session keeps its transcript at phone width`() =
        withSession("BUSY", providerSessionId = "p-1") { assertTranscriptFillsTheColumn("busy") }

    @Test
    fun `an exited session keeps its transcript at phone width`() =
        withSession("EXITED", providerSessionId = "p-1") { assertTranscriptFillsTheColumn("exited") }

    @Test
    fun `a failed session that cannot resume keeps its transcript at phone width`() =
        withSession("FAILED", providerSessionId = null) { assertTranscriptFillsTheColumn("failed") }

    @Test
    fun `an idle session keeps its transcript with the keyboard up`() =
        // A ~411dp phone with the soft keyboard open leaves about this much window.
        withSession("IDLE", providerSessionId = "p-1", width = 411, height = 520) {
            val transcript = bounds("center-transcript")
            assertTrue(transcript.height >= 100f, "keyboard up: the transcript still shows (was ${transcript.height}dp)")
        }
}
