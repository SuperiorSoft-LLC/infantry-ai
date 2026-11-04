// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.agents.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** AGENT-MENU-1: screenshots of the agent card menu (with / without a ticket) and the ticket picker (empty / filtered search) under build/agent-menu-shots; asserts nothing. */
@OptIn(ExperimentalTestApi::class)
class AgentMenuShots {
    private fun t(id: String, title: String, weight: Int? = null, held: Boolean = false) =
        Ticket(id = "ticket-$id", title = title, status = "todo", weight = weight, holdReason = if (held) "waiting" else null)
    private val running = t("0df614d6", "AGENT-MENU-1: agent card menu - no long ticket list; pick another ticket in a modal", 3).copy(status = "in_progress")
    private val todo = listOf(
        t("a1b2c3d4", "CHAT-MINIMIZE-5: restore button keeps focus", 2), t("b2c3d4e5", "ANDROID-12: share sheet crashes on rotate", 4),
        t("c3d4e5f6", "DOCS-9: wiki page for harness plugins", 1), t("d4e5f6a7", "[HOLD] LICENSE-2: lawyer review", 5, held = true),
        t("e5f6a7b8", "[PARKED] PLUGIN-TREE-1: nested blocks"), t("f6a7b8c9", "SEARCH-3: filter chips on the board", 3),
    )

    private fun shoot(name: String, w: Int, h: Int, dark: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        Palette.theme = ThemeSettings(dark = dark)
        runDesktopComposeUiTest(w, h) {
            setContent { InfantryTheme { Box(Modifier.fillMaxWidth().background(Palette.Bg).padding(8.dp)) { content() } } }
            waitForIdle()
            val dir = File("build/agent-menu-shots").apply { mkdirs() }
            ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        }
        Palette.theme = ThemeSettings()
    }

    private fun menu(cur: Ticket?, w: Int) = @androidx.compose.runtime.Composable {
        Column(Modifier.width(w.dp).background(Palette.Raised).border(1.dp, Palette.Border)) {
            DropdownMenuItem(text = { Text("Open", color = Palette.Text, fontSize = 12.sp) }, onClick = {})
            HorizontalDivider(color = Palette.Border)
            AgentMenuTicketItems("a", cur, {}, {})
            HorizontalDivider(color = Palette.Border)
            DropdownMenuItem(text = { Text("Remove", color = Palette.Danger, fontSize = 12.sp) }, onClick = {})
        }
    }

    private fun picker(query: String, held: Boolean) = @androidx.compose.runtime.Composable { TicketPickerPanel("a", todo, {}, {}, query, held) }

    @Test fun all() {
        for ((mode, dark) in listOf("dark" to true, "light" to false)) for ((size, w) in listOf("desktop" to 900, "phone" to 400)) {
            val mw = if (w < 500) 240 else 260
            shoot("menu-ticket-$mode-$size", w, 220, dark, menu(running, mw))
            shoot("menu-noticket-$mode-$size", w, 190, dark, menu(null, mw))
            shoot("picker-empty-$mode-$size", w, 640, dark, picker("", false))
            shoot("picker-filtered-$mode-$size", w, 360, dark, picker("an", true))
        }
    }
}
