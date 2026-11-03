package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.isSlashCommand
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CP-4(d): what the window does with a `/command`'s outcome, and which input counts as a command at all. */
class SlashExecuteModelTest {
    @Test
    fun aCommandResultBecomesAnInfoNoticeAndNeverAChatLine() {
        val frame = Envelope("command_result", """{"ok":true,"command":"effort","message":"effort set to high; it applies from the next turn"}""", "s1")
        val state = reduce(AppState(), frame, 7L)
        val notice = state.notices.single()
        assertEquals("effort set to high; it applies from the next turn", notice.text)
        assertTrue(notice.info, "a command that worked is not drawn like a failure")
        assertEquals("s1", notice.sessionId)
        assertTrue(state.agents.isEmpty(), "the result is not a transcript line of any agent")
    }

    @Test
    fun aRefusedCommandStaysAnErrorNotice() {
        val state = reduce(AppState(), Envelope(Envelope.ERROR, "unknown command '/frobnicate'; known commands: /model, /effort, /compact"), 7L)
        assertFalse(state.notices.single().info)
    }

    @Test
    fun aLeadingSlashIsACommandAndASlashLaterInTheTextIsNot() {
        assertTrue(isSlashCommand("/effort high"))
        assertTrue(isSlashCommand("  /compact"))
        assertFalse(isSlashCommand("please read /usr/bin"))
        assertFalse(isSlashCommand("hello"))
    }
}
