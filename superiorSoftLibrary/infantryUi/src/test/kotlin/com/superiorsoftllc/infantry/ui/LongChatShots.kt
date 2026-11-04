// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** CHAT-SCROLL-1: PNGs of the seeded long chat (bottom, scrolled up with jump-to-latest, a search hit), dark/light, desktop/phone, under build/long-chat-shots; asserts nothing. */
@OptIn(ExperimentalTestApi::class)
class LongChatShots {
    private enum class View { BOTTOM, SCROLLED_UP, SEARCH }

    private fun shoot(name: String, w: Int, h: Int, dark: Boolean, view: View) {
        Palette.theme = ThemeSettings(dark = dark)
        resetActivityBoxChoice()
        val agent = mutableStateOf(LongChatFixture.agent(800))
        runDesktopComposeUiTest(w, h) {
            setContent { InfantryTheme { Box(Modifier.fillMaxSize().background(Palette.Bg)) { AgentChat(agent.value) } } }
            waitForIdle()
            when (view) {
                View.BOTTOM -> Unit
                View.SCROLLED_UP -> {
                    onNodeWithTag(CHAT_TRANSCRIPT_TAG).performScrollToIndex(20)
                    waitForIdle()
                    var seq = 100_000L
                    repeat(12) { i ->
                        agent.value = agent.value.copy(entries = agent.value.entries + TranscriptEntry(seq++, LineKind.LOG, "live line $i", 1_900_000_000_000L + i))
                        waitForIdle()
                    }
                }
                View.SEARCH -> {
                    onNodeWithTag(CHAT_SEARCH_CHIP_TAG).performClick()
                    waitForIdle()
                    onNodeWithTag(CHAT_SEARCH_FIELD_TAG).performTextInput("ticket-00001000")
                    waitForIdle()
                }
            }
            val dir = File("build/long-chat-shots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
        Palette.theme = ThemeSettings()
    }

    @Test fun darkDesktopBottom() = shoot("dark-desktop-bottom", 900, 640, true, View.BOTTOM)
    @Test fun lightDesktopBottom() = shoot("light-desktop-bottom", 900, 640, false, View.BOTTOM)
    @Test fun darkDesktopScrolledUp() = shoot("dark-desktop-scrolled-up", 900, 640, true, View.SCROLLED_UP)
    @Test fun lightDesktopScrolledUp() = shoot("light-desktop-scrolled-up", 900, 640, false, View.SCROLLED_UP)
    @Test fun darkDesktopSearch() = shoot("dark-desktop-search", 900, 640, true, View.SEARCH)
    @Test fun lightDesktopSearch() = shoot("light-desktop-search", 900, 640, false, View.SEARCH)
    @Test fun darkPhoneBottom() = shoot("dark-phone-bottom", 400, 760, true, View.BOTTOM)
    @Test fun lightPhoneBottom() = shoot("light-phone-bottom", 400, 760, false, View.BOTTOM)
    @Test fun darkPhoneScrolledUp() = shoot("dark-phone-scrolled-up", 400, 760, true, View.SCROLLED_UP)
    @Test fun lightPhoneScrolledUp() = shoot("light-phone-scrolled-up", 400, 760, false, View.SCROLLED_UP)
    @Test fun darkPhoneSearch() = shoot("dark-phone-search", 400, 760, true, View.SEARCH)
    @Test fun lightPhoneSearch() = shoot("light-phone-search", 400, 760, false, View.SEARCH)
}
