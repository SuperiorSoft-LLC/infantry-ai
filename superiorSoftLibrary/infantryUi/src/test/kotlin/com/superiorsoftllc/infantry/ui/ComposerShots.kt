// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.ui.model.AgentActivity
import com.superiorsoftllc.infantry.ui.model.AgentView
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

/** CHAT-MINIMIZE-3/5: screenshots (narrow = the owner's ~350dp chat) of the composer (dark/light, desktop/phone width, normal/expanded/minimized, Activity hover) under build/composer-shots; asserts nothing. */
@OptIn(ExperimentalTestApi::class)
class ComposerShots {
    private val agent = AgentView(
        session = SessionRecord(id = "a", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p"),
        activityLog = List(200) { AgentActivity("bash", "gradle test", 1L) },
    )

    private fun shoot(name: String, w: Int, dark: Boolean, expanded: Boolean = false, minimized: Boolean = false, hover: Boolean = false, focused: Boolean = false) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Palette.theme = ThemeSettings(dark = dark)
        resetActivityBoxChoice()
        runDesktopComposeUiTest(w, if (expanded) 480 else 300) {
            val controller = Controller(scope)
            val chat = ChatPanelState().also { it.draft = "half-written prompt" }
            setContent {
                InfantryTheme {
                    Box(Modifier.fillMaxWidth().background(Palette.Bg).padding(8.dp)) {
                        Column {
                            // CHAT-MINIMIZE-5: the float row (Expand, Minimize, Activity) above the box, as the feed draws it.
                            val floaters: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                                ComposerFloaters(minimized, expanded, {}, {}, {})
                            }
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { ActivityBox(agent, floaters) }
                            if (!minimized) PromptBox(chat.draft, chat::edit, {}, chat.focus, false, agent, controller, {}, expanded = expanded)
                        }
                    }
                }
            }
            waitForIdle()
            if (focused) runOnIdle { chat.focus.requestFocus() }
            waitForIdle()
            if (hover) onNodeWithTag(ACTIVITY_BOX_TAG).performMouseInput { moveTo(center) }
            waitForIdle()
            val dir = File("build/composer-shots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
        Palette.theme = ThemeSettings()
    }

    @Test fun darkWide() = shoot("dark-wide", 900, true)
    @Test fun lightWide() = shoot("light-wide", 900, false)
    @Test fun darkPhone() = shoot("dark-phone", 400, true)
    @Test fun lightPhone() = shoot("light-phone", 400, false)
    @Test fun darkExpanded() = shoot("dark-expanded", 900, true, expanded = true)
    @Test fun darkFocused() = shoot("dark-focused", 900, true, focused = true)
    @Test fun lightFocused() = shoot("light-focused", 900, false, focused = true)
    @Test fun lightPhoneFocused() = shoot("light-phone-focused", 400, false, focused = true)
    @Test fun darkPhoneFocused() = shoot("dark-phone-focused", 400, true, focused = true)
    @Test fun darkHover() = shoot("dark-hover", 900, true, hover = true)
    @Test fun darkMinimized() = shoot("dark-minimized", 400, true, minimized = true)
    @Test fun darkNarrow() = shoot("dark-narrow", 366, true)
    @Test fun lightNarrow() = shoot("light-narrow", 366, false)
    @Test fun darkNarrowFocused() = shoot("dark-narrow-focused", 366, true, focused = true)
    @Test fun lightNarrowFocused() = shoot("light-narrow-focused", 366, false, focused = true)
    @Test fun lightExpanded() = shoot("light-expanded", 900, false, expanded = true)
    @Test fun darkPhoneTiny() = shoot("dark-phone-360", 360, true)
}
