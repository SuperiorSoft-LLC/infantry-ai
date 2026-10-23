package com.superiorsoftllc.agenthq.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CHAT-PANEL-1 / ASSISTANT-1: the chat has ONE implementation. The main window and the Assistant popup both host
 * `ChatPanel`, and neither composes a composer, a transcript or a header of its own.
 */
class ChatPanelOneSourceTest {
    private val ui = "../agenthqUiCore/src/commonMain/kotlin/com/superiorsoftllc/agenthq/ui"
    private fun src(name: String) = File("$ui/$name").readText()

    @Test fun `the main window and the assistant popup both host ChatPanel`() {
        assertTrue(Regex("""\bChatPanel\(""").containsMatchIn(src("App.kt")), "App.kt hosts ChatPanel")
        assertTrue(Regex("""\bChatPanel\(""").containsMatchIn(src("AssistantPopup.kt")), "AssistantPopup.kt hosts ChatPanel")
    }

    @Test fun `neither host builds its own chat parts`() {
        for (host in listOf("App.kt", "AssistantPopup.kt")) {
            val text = src(host)
            for (part in listOf("PromptBox(", "AgentChat(", "CenterHeader(", "ActivityBox(", "SlashSuggestions(")) {
                assertTrue(!text.contains(part), "$host must not compose $part itself; ChatPanel does")
            }
        }
    }

    @Test fun `ChatPanel is the one place that composes the chat parts`() {
        val panel = src("ChatPanel.kt")
        assertEquals(1, Regex("""\bPromptBox\(""").findAll(panel).count())
        assertTrue(panel.contains("OutputPane("))
    }
}
