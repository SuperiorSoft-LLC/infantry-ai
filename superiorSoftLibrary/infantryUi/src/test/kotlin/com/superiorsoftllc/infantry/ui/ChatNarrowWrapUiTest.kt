// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
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
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import java.io.File
import androidx.compose.ui.test.hasText
import javax.imageio.ImageIO

/**
 * FORMAT-1 (ticket f759bfa2): the real window at desktop and narrow widths with a markdown answer holding a numbered
 * list: every line of the answer, its list markers included, lies inside the window (nothing clipped on the left),
 * and the status bar starts at the window's left edge. PNGs go to build/format1-shots.
 */
@OptIn(ExperimentalTestApi::class)
class ChatNarrowWrapUiTest {
    private val sid = "s-fmt"
    private val md = "The updater has **no repo set by default**: `updater.repo` is empty, so a fresh install never checks anywhere.\n\n**To get 1.0.6 now:**\n\n1. Open **Configuration > About > Updater**.\n2. Set GitHub repo to `SuperiorSoft-LLC/infantry-ai`.\n3. Press **Check for updates**, or restart the app.\n"

    @Test fun theAnswerAndItsListMarkersStayInsideTheWindowAtEveryWidth() {
        for (w in listOf(1440, 1100, 900, 760)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val link = ChangesFakeLink(scope)
                val controller = Controller(scope, link)
                runDesktopComposeUiTest(w, 720) {
                    setContent { App(controller, "ws://127.0.0.1:1/ws", null) }
                    link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                    link.push(Envelope(Envelope.SESSIONS, """[{"id":"$sid","provider":"claude","workingDir":"/w","spawnedAt":1,"lastHeartbeat":1,"state":"IDLE"}]"""))
                    waitUntil(timeoutMillis = 10_000) { controller.state.value.agents.containsKey(sid) }
                    controller.select(sid)
                    link.push(Envelope(Envelope.TEXT_DELTA, md, sid))
                    waitForIdle()
                    val dir = File("build/format1-shots").apply { mkdirs() }
                    ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "app-$w.png"))
                    val window = onRoot().fetchSemanticsNode().boundsInRoot
                    val lines = onAllNodes(hasText("Configuration > About > Updater", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
                    assertTrue(lines.isNotEmpty(), "$w: the list item is composed")
                    for (n in lines) {
                        val b = n.boundsInRoot
                        assertTrue(b.left >= window.left && b.right <= window.right + 0.5f, "$w: the list line lies inside the window: $b in $window")
                    }
                    val transcript = onNodeWithTag("center-transcript").fetchSemanticsNode().boundsInRoot
                    assertTrue(transcript.left >= window.left && transcript.right <= window.right + 0.5f, "$w: the transcript lies inside the window: $transcript")
                }
            } finally { scope.cancel() }
        }
    }
}
