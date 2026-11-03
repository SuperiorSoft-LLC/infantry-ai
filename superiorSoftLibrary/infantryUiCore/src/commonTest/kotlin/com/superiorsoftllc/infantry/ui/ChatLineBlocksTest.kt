package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.chatLineBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatLineBlocksTest {
    @Test
    fun chatLineRendersAsBlocksOnlyForAssistantText() {
        assertEquals(
            listOf(ChatBlock.Paragraph("hi"), ChatBlock.Code("kt", "x")),
            chatLineBlocks(LineKind.TEXT, "hi\n```kt\nx\n```"),
        )
        assertEquals(listOf(ChatBlock.Code("", "open")), chatLineBlocks(LineKind.TEXT, "```\nopen"))
        for (k in listOf(LineKind.USER, LineKind.REASONING, LineKind.STATUS, LineKind.ERROR, LineKind.FINISHED)) {
            assertNull(chatLineBlocks(k, "```\nx\n```"), "$k stays plain")
        }
    }
}
