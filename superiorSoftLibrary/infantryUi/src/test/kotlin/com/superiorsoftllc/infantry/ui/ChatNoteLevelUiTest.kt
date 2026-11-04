// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.infantry.plugins.official.chatfeed.CONTRIBUTION_MARK_TAG
import com.superiorsoftllc.infantry.plugins.official.chatfeed.ContributionRow
import com.superiorsoftllc.infantry.ui.model.ThemeSettings
import com.superiorsoftllc.infantry.ui.model.TimelineItem
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CHAT-NOTE-WARN-1: info / ok / warn / fail chat notes side by side, dark and light, as PNGs under build/chat-note-level-shots;
 * a warn note reads as warn with a warning mark, an unknown level from another daemon reads as info.
 */
@OptIn(ExperimentalTestApi::class)
class ChatNoteLevelUiTest {
    private fun note(level: String, text: String, ok: Boolean = true) =
        TimelineItem("note-$level", "s1", 1_762_250_000_000, "automation", "AUTOMATION · note", "AUTOMATION · $text", ok = ok, meta = mapOf("level" to level))

    private val notes = listOf(
        note("info", "worker-watch: 1 session idle"),
        note("ok", "build ok in 41 s"),
        note("warn", "memory low: 3750 MB free · WARNING"),
        note("fail", "gate failed: 2 tests · FAILED", ok = false),
    )

    @Test fun `levels are read from the wire, unknown levels as info`() {
        val json = Json { ignoreUnknownKeys = true }
        val warn = json.decodeFromString(TimelineItem.serializer(), """{"id":"n","sessionId":"s","at":1,"source":"automation","text":"t","ok":true,"meta":{"level":"warn"}}""")
        assertEquals("warn", warn.noteLevel)
        assertEquals(listOf("info", "ok", "warn", "fail"), notes.map { it.noteLevel })
        assertEquals("info", note("loud", "x").noteLevel)
        assertEquals("info", TimelineItem("r", "s", 1).noteLevel)
        assertEquals("fail", note("warn", "x", ok = false).noteLevel)
    }

    @Test fun `warn has the warning mark, the others the gear, screenshots dark and light`() {
        for (dark in listOf(true, false)) {
            Palette.theme = ThemeSettings(dark = dark)
            try {
                runDesktopComposeUiTest(520, 190) {
                    setContent { InfantryTheme { Column(Modifier.fillMaxWidth().background(Palette.Bg).padding(8.dp)) { notes.forEach { ContributionRow(it) } } } }
                    waitForIdle()
                    val marks = onAllNodesWithTag(CONTRIBUTION_MARK_TAG, useUnmergedTree = true)
                    listOf("⚙", "⚙", "⚠", "⚙").forEachIndexed { i, m -> marks[i].assertTextEquals(m) }
                    val dir = File("build/chat-note-level-shots").apply { mkdirs() }
                    ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(dir, "notes-${if (dark) "dark" else "light"}.png"))
                }
            } finally { Palette.theme = ThemeSettings() }
        }
    }
}
