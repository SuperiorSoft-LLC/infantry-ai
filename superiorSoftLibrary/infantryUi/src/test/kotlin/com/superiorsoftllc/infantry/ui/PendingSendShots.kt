// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

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
import com.superiorsoftllc.infantry.plugins.official.chatfeed.PENDING_SEND_DISCARD_TAG
import com.superiorsoftllc.infantry.plugins.official.chatfeed.PENDING_SEND_RETRY_TAG
import com.superiorsoftllc.infantry.plugins.official.chatfeed.PENDING_SEND_TAG
import com.superiorsoftllc.infantry.plugins.official.chatfeed.PendingSendRow
import com.superiorsoftllc.infantry.ui.model.PendingSend
import com.superiorsoftllc.infantry.ui.model.SendState
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CHAT-SENDING-1: screenshots of a sent message's bubble in its three states (sending..., sent, not sent - retry), dark/light,
 * desktop/phone width (phone = 48dp touch targets on retry and discard), under build/sending-shots.
 */
@OptIn(ExperimentalTestApi::class)
class PendingSendShots {
    private val now = 1_762_300_000_000L
    private val sends = listOf(
        PendingSend("send:1", "a", "run the tests and post the summary", now, SendState.SENDING),
        PendingSend("send:2", "a", "then open the PR", now, SendState.ACCEPTED),
        PendingSend("send:3", "a", "and ping the reviewer", now, SendState.FAILED, reason = "no answer in 45s"),
    )

    private fun shoot(name: String, w: Int, dark: Boolean) {
        Palette.theme = ThemeSettings(dark = dark)
        runDesktopComposeUiTest(w, 360) {
            setContent {
                InfantryTheme {
                    Box(Modifier.fillMaxWidth().background(Palette.Bg).padding(8.dp)) {
                        Column(Modifier.background(Palette.Panel).padding(10.dp)) {
                            sends.forEach { PendingSendRow(it, onRetry = {}, onDiscard = {}, compact = w < 600) }
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals(3, onAllNodesWithTag(PENDING_SEND_TAG).fetchSemanticsNodes().size)
            assertEquals(1, onAllNodesWithTag(PENDING_SEND_RETRY_TAG).fetchSemanticsNodes().size, "only the failed one offers retry")
            assertEquals(1, onAllNodesWithTag(PENDING_SEND_DISCARD_TAG).fetchSemanticsNodes().size)
            val dir = File("build/sending-shots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
        Palette.theme = ThemeSettings()
    }

    @Test fun darkDesktop() = shoot("dark-desktop", 900, true)
    @Test fun lightDesktop() = shoot("light-desktop", 900, false)
    @Test fun darkPhone() = shoot("dark-phone", 400, true)
    @Test fun lightPhone() = shoot("light-phone", 400, false)
}
