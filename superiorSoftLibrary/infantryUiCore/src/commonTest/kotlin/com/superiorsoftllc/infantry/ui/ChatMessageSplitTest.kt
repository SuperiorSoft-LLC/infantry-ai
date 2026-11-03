package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.TranscriptEntry
import com.superiorsoftllc.infantry.ui.model.render
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMessageSplitTest {
    private fun text(t: String, closed: Boolean = false) = TranscriptEntry(null, LineKind.TEXT, t, 0, closed)

    @Test
    fun streamingDeltasOfOneMessageStillJoin() {
        assertEquals(listOf("Hello world"), render(listOf(text("Hello "), text("world"))).map { it.text })
    }

    @Test
    fun textClosedByAToolFrameStartsANewMessage() {
        val out = render(listOf(text("Build passed.", closed = true), text("All green.")))
        assertEquals(listOf("Build passed.", "All green."), out.map { it.text })
    }

    @Test
    fun twoMessagesWithAVisibleEntryBetweenStaySeparate() {
        val out = render(listOf(text("one", closed = true), TranscriptEntry(null, LineKind.STATUS, "tool", 0), text("two")))
        assertEquals(listOf("one", "tool", "two"), out.map { it.text })
    }
}
