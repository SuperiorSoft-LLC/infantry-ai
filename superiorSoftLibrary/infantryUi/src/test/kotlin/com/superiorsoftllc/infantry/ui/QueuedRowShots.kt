// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.QueuedPrompt
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CHAT-QUEUE-1: screenshots of the queued rows above the composer, each with Force (Send now) and X (Remove from queue) at its right
 * end, dark/light, desktop/phone width (phone = 48dp touch targets), under build/queue-shots. Asserts the rows and actions exist.
 */
@OptIn(ExperimentalTestApi::class)
class QueuedRowShots {
    private val agent = AgentView(
        session = SessionRecord(id = "a", provider = "claude", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p"),
    )
    private val queue = listOf(
        QueuedPrompt("also run the lint check before you commit", 1, id = "q-1"),
        QueuedPrompt("and post the test summary on the ticket", 2, id = "q-2"),
    )

    private fun shoot(name: String, w: Int, dark: Boolean, canForce: Boolean = true) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Palette.theme = ThemeSettings(dark = dark)
        runDesktopComposeUiTest(w, 300) {
            val controller = Controller(scope)
            val chat = ChatPanelState().also { it.draft = "" }
            setContent {
                InfantryTheme {
                    Box(Modifier.fillMaxWidth().background(Palette.Bg).padding(8.dp)) {
                        Column(Modifier.background(Palette.Panel).padding(horizontal = 10.dp)) {
                            QueuedMessages(queue, onRemove = {}, onForce = {}, canForce = canForce, compact = w < 600)
                            PromptBox(chat.draft, chat::edit, {}, chat.focus, false, agent, controller, {})
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals(2, onAllNodesWithTag(QUEUED_ROW_TAG).fetchSemanticsNodes().size, "one line per queued message")
            assertEquals(2, onAllNodesWithTag(QUEUED_REMOVE_TAG).fetchSemanticsNodes().size)
            assertEquals(2, onAllNodesWithTag(QUEUED_FORCE_TAG).fetchSemanticsNodes().size)
            val dir = File("build/queue-shots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
        Palette.theme = ThemeSettings()
    }

    @Test fun darkDesktop() = shoot("dark-desktop", 900, true)
    @Test fun lightDesktop() = shoot("light-desktop", 900, false)
    @Test fun darkPhone() = shoot("dark-phone", 400, true)
    @Test fun lightPhone() = shoot("light-phone", 400, false)
    @Test fun darkDesktopNoForce() = shoot("dark-desktop-no-midturn", 900, true, canForce = false)
}
