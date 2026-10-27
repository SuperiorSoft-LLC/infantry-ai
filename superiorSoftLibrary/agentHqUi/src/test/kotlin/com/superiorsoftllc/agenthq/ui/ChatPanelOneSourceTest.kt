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
    // S4c: a host that moved to the app UI (plugins/core/Infantry_AppUi) is read from there.
    private val app = "../../plugins/core/Infantry_AppUi"
    private fun src(name: String) = (File("$ui/$name").takeIf { it.exists() } ?: File("$app/${name.substringAfterLast('/')}")).readText()

    @Test fun `the main window centre block and the assistant popup both host ChatPanel`() {
        // BLOCKS-LAYOUT L9b: the centre block hosts ChatPanel's own sections, one node each, through chatPanelHost.
        assertTrue(Regex("""\bchatPanelHost\(""").containsMatchIn(src("shell/blocks/ShellCenter.kt")), "ShellCenter.kt (the main window centre block) hosts ChatPanel's sections")
        assertTrue(Regex("""\bChatPanel\(""").containsMatchIn(src("AssistantPopup.kt")), "AssistantPopup.kt hosts ChatPanel")
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
        assertTrue(panel.contains("OutputPane("))
    }
}
