// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.model.sessionPaneAction
import com.superiorsoftllc.infantry.ui.protocol.KillOutcome
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * KILL-BLANK-1: a session killed mid-turn (EXITED, with a provider session id, killOutcome set) keeps its transcript
 * on screen under a Resume bar; the "Nothing on screen" splash is only for a session with nothing to show. Writes
 * PNGs under build/kill-blank-shots for a look.
 */
@OptIn(ExperimentalTestApi::class)
class KillBlankUiTest {
    private val prompt = "Write a 1500-word essay about deserts. No tools."

    private fun killed(entries: List<TranscriptEntry>) = AgentView(
        SessionRecord(
            "4cd48f46", "claude", "/repo", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.EXITED,
            providerSessionId = "p-1", runCount = 1, model = "haiku", killOutcome = KillOutcome.GRACEFUL,
        ),
        entries = entries,
    )

    private fun shoot(a: AgentView, name: String, check: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(720, 420) {
            setContent {
                InfantryTheme {
                    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
                        OutputPane(a, spawnMode = false, onStartSession = null, paneAction = sessionPaneAction(a.session, emptyList()))
                    }
                }
            }
            waitForIdle()
            val dir = File("build/kill-blank-shots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
            check()
        }

    @Test fun `a killed session keeps its prompt and lines under a Resume bar`() {
        val a = killed(
            listOf(
                TranscriptEntry(1, LineKind.USER, prompt, 1_000),
                TranscriptEntry(2, LineKind.STATUS, "[infantry] * session started (claude - haiku)", 1_100),
                TranscriptEntry(3, LineKind.FINISHED, "finished: killed graceful", 7_000),
            ),
        )
        shoot(a, "killed-with-transcript") {
            onAllNodesWithText("Nothing on screen", substring = true).assertCountEquals(0)
            onAllNodesWithText(prompt, substring = true).assertCountEquals(1)
            onAllNodesWithTag(SESSION_ENDED_BAR_TAG).assertCountEquals(1)
            onAllNodesWithTag(RESUME_SESSION_TAG).assertCountEquals(1)
        }
    }

    @Test fun `an ended session with nothing on screen still shows the Resume splash`() {
        shoot(killed(emptyList()), "ended-empty") {
            onAllNodesWithText("Nothing on screen", substring = true).assertCountEquals(1)
            onAllNodesWithTag(SESSION_ENDED_BAR_TAG).assertCountEquals(0)
            onAllNodesWithTag(RESUME_SESSION_TAG).assertCountEquals(1)
        }
    }
}
