package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatSpan
import com.superiorsoftllc.agenthq.ui.model.ColumnAlign
import com.superiorsoftllc.agenthq.ui.model.parseChatInline
import com.superiorsoftllc.agenthq.ui.model.parseChatMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatMarkdownTableTest {
    private fun table(text: String, streaming: Boolean = false) = parseChatMarkdown(text, streaming).filterIsInstance<ChatBlock.Table>().single()

    @Test
    fun `alignment comes from the separator row`() {
        val t = table("| a | b | c | d |\n|---|:--|:-:|--:|\n| 1 | 2 | 3 | 4 |")
        assertEquals(listOf(ColumnAlign.NONE, ColumnAlign.START, ColumnAlign.CENTER, ColumnAlign.END), t.alignments)
        assertEquals(listOf("a", "b", "c", "d"), t.header)
        assertEquals(listOf(listOf("1", "2", "3", "4")), t.rows)
    }

    @Test
    fun `ragged rows are padded and truncated to the header`() {
        val t = table("| a | b |\n|---|---|\n| 1 |\n| 1 | 2 | 3 |")
        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), t.rows)
    }

    @Test
    fun `an escaped pipe stays inside its cell`() {
        val t = table("| a | b |\n|---|---|\n| x \\| y | z |")
        assertEquals(listOf(listOf("x | y", "z")), t.rows)
    }

    @Test
    fun `rows without outer pipes parse`() {
        val t = table("a | b\n--|--\n1 | 2")
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", "2")), t.rows)
    }

    @Test
    fun `inline markup in a cell parses through the inline parser`() {
        val t = table("| a |\n|---|\n| **bold** `c` |")
        assertEquals(listOf(ChatSpan.Bold("bold"), ChatSpan.Text(" "), ChatSpan.InlineCode("c")), parseChatInline(t.rows.single().single()))
    }

    @Test
    fun `a table after a paragraph is its own block`() {
        val blocks = parseChatMarkdown("Results:\n| a | b |\n|---|---|\n| 1 | 2 |\n\nDone")
        assertEquals(3, blocks.size)
        assertEquals(ChatBlock.Paragraph("Results:"), blocks[0])
        assertIs<ChatBlock.Table>(blocks[1])
        assertEquals(ChatBlock.Paragraph("Done"), blocks[2])
    }

    @Test
    fun `pipe text that is not a table stays a paragraph`() {
        assertEquals(listOf(ChatBlock.Paragraph("a | b")), parseChatMarkdown("a | b"))
        assertEquals(listOf(ChatBlock.Paragraph("a | b\nc | d")), parseChatMarkdown("a | b\nc | d"))
        // Separator with a different column count is not a table.
        assertEquals(emptyList(), parseChatMarkdown("a | b\n---|---|---").filterIsInstance<ChatBlock.Table>())
    }

    @Test
    fun `a table inside a code fence stays code`() {
        val blocks = parseChatMarkdown("```\n| a | b |\n|---|---|\n| 1 | 2 |\n```")
        assertEquals(listOf(ChatBlock.Code("", "| a | b |\n|---|---|\n| 1 | 2 |")), blocks)
    }

    @Test
    fun `streaming shows a half-received table as a paragraph until the separator is complete`() {
        assertEquals(listOf(ChatBlock.Paragraph("| a | b |")), parseChatMarkdown("| a | b |", streaming = true))
        assertEquals(listOf(ChatBlock.Paragraph("| a | b |\n|--")), parseChatMarkdown("| a | b |\n|--", streaming = true))
        assertEquals(listOf(ChatBlock.Paragraph("| a | b |\n|---|---")), parseChatMarkdown("| a | b |\n|---|---", streaming = true))
        assertEquals(ColumnAlign.NONE, table("| a | b |\n|---|---|", streaming = true).alignments[0])
        assertEquals(emptyList(), table("| a | b |\n|---|---|\n| 1 |", streaming = true).rows.drop(1))
    }
}
