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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** CHAT-PAD-1: chat empty states and a transcript at wide / popout / phone widths, dark and light, under build/chat-pad-shots; asserts nothing. */
@OptIn(ExperimentalTestApi::class)
class ChatPaddingShots {
    private fun view(global: Boolean, lines: List<TranscriptEntry> = emptyList()) = AgentView(
        session = SessionRecord(id = "s1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, global = global),
        entries = lines,
    )

    private fun shoot(name: String, w: Int, dark: Boolean, a: AgentView) {
        Palette.theme = ThemeSettings(dark = dark)
        runDesktopComposeUiTest(w, 420) {
            setContent { InfantryTheme { Box(Modifier.fillMaxSize().background(Palette.Bg)) { OutputPane(a, spawnMode = false, onStartSession = null) } } }
            waitForIdle()
            val dir = File("build/chat-pad-shots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
        Palette.theme = ThemeSettings()
    }

    @Test fun all() {
        val msgs = listOf(
            TranscriptEntry(1, LineKind.USER, "Why does the board not refresh after I move a ticket on the phone client?", 1),
            TranscriptEntry(2, LineKind.TEXT, "The board listens for ticket frames over the WebSocket; a remote client that reconnects misses the frames sent while it was away, so it shows the old column until the next full refresh.", 1, closed = true),
        )
        for ((mode, dark) in listOf("dark" to true, "light" to false)) for ((size, w) in listOf("wide" to 900, "popout" to 350, "phone" to 400)) {
            shoot("assistant-empty-$mode-$size", w, dark, view(true))
            shoot("main-empty-$mode-$size", w, dark, view(false))
            shoot("messages-$mode-$size", w, dark, view(false, msgs))
        }
    }
}
