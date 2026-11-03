// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CHAT-PANEL-1 / ASSISTANT-1: the chat has ONE implementation. The main window and the Assistant popup both host
 * `ChatPanel`, and neither composes a composer, a transcript or a header of its own.
 */
class ChatPanelOneSourceTest {
    private val ui = "../infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui"
    // RUNTIME-26: the app UI module is retired; no host is read from it.
    private val app = "../../plugins/core"
    // RUNTIME-24: the Assistant popup is the official Infantry_Assistant's.
    private val assistant = "../../plugins/official/Infantry_Assistant/src"
    // RUNTIME-22: the centre block and ChatPanel are the official Infantry_Chat's.
    private val chat = "../../plugins/official/Infantry_Chat/src"
    private fun src(name: String) = (File("$ui/$name").takeIf { it.exists() } ?: File("$app/${name.substringAfterLast('/')}").takeIf { it.exists() } ?: File("$assistant/${name.substringAfterLast('/')}").takeIf { it.exists() } ?: File("$chat/${name.substringAfterLast('/')}")).readText()

    @Test fun `the main window centre block and the assistant popup both host ChatPanel`() {
        // BLOCKS-LAYOUT L9b: the centre block hosts ChatPanel's own sections, one node each, through chatPanelHost.
        assertTrue(Regex("""\bchatPanelHost\(""").containsMatchIn(src("shell/blocks/ShellCenter.kt")), "ShellCenter.kt (the main window centre block) hosts ChatPanel's sections")
        assertTrue(src("AssistantPopup.kt").contains("ChatPanels.panel"), "AssistantPopup.kt hosts ChatPanel (through the panel Infantry_Chat publishes)")
    }

    @Test fun `neither host builds its own chat parts`() {
        for (host in listOf("App.kt", "AssistantPopup.kt", "shell/blocks/ShellCenter.kt")) {
            val text = src(host)
            for (part in listOf("PromptBox(", "AgentChat(", "CenterHeader(", "ActivityBox(", "SlashSuggestions(")) {
                assertTrue(!text.contains(part), "$host must not compose $part itself; ChatPanel does")
            }
        }
    }

    @Test fun `ChatPanel is the one place that composes the chat parts`() {
        val panel = src("ChatPanel.kt")
        assertEquals(1, Regex("""\bPromptBox\(""").findAll(panel).count())
        // CHAT-FEED-SPLIT-1: the transcript is the feed child plugin in the chat.feed slot, not drawn by ChatPanel.
        assertTrue(panel.contains("ChildSlot("))
    }
}
