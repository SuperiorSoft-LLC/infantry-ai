package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ChatBlock
import com.superiorsoftllc.agenthq.ui.model.ChatSpan
import com.superiorsoftllc.agenthq.ui.model.ColumnAlign
import com.superiorsoftllc.agenthq.ui.model.isSafeChatUrl
import com.superiorsoftllc.agenthq.ui.model.parseChatInline
import com.superiorsoftllc.agenthq.ui.model.parseChatMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Per-element tests of the shared chat renderer model (italic, lists, quote, table, rule, link safety). */
class ChatMarkdownRichTest {
    @Test
    fun italicWithStarAndUnderscore() {
        assertEquals(
            listOf(ChatSpan.Text("a "), ChatSpan.Italic("b"), ChatSpan.Text(" c "), ChatSpan.Italic("d")),
            parseChatInline("a *b* c _d_"),
        )
        // snake_case and lone/spaced stars are not italic
        assertEquals(listOf(ChatSpan.Text("my_var_name and 2 * 3 * 4")), parseChatInline("my_var_name and 2 * 3 * 4"))
    }

    @Test
    fun bulletVariantsAndOrderedList() {
        assertEquals(listOf(ChatBlock.BulletList(listOf("a", "b"))), parseChatMarkdown("* a\n+ b"))
        assertEquals(listOf(ChatBlock.BulletList(listOf("x", "y"), ordered = true)), parseChatMarkdown("1. x\n2) y"))
        assertEquals(
            listOf(ChatBlock.BulletList(listOf("a")), ChatBlock.BulletList(listOf("b"), ordered = true)),
            parseChatMarkdown("- a\n1. b"),
        )
    }

    @Test
    fun quoteTableAndRule() {
        assertEquals(listOf(ChatBlock.Quote("one\ntwo")), parseChatMarkdown("> one\n> two"))
        assertEquals(
            listOf(ChatBlock.Paragraph("a"), ChatBlock.Rule, ChatBlock.Paragraph("b")),
            parseChatMarkdown("a\n\n---\n\nb"),
        )
        assertEquals(
            listOf(ChatBlock.Table(listOf("k", "v"), listOf(ColumnAlign.NONE, ColumnAlign.CENTER), listOf(listOf("1", "2"), listOf("3", "")))),
            parseChatMarkdown("| k | v |\n|---|:-:|\n| 1 | 2 |\n| 3 |"),
        )
        // a pipe line without a separator row is just text
        assertEquals(listOf(ChatBlock.Paragraph("a | b")), parseChatMarkdown("a | b"))
    }

    @Test
    fun headingAndQuoteCarryInlineSpans() {
        assertEquals(listOf(ChatSpan.Bold("x")), (parseChatMarkdown("# **x**").single() as ChatBlock.Heading).spans)
    }

    @Test
    fun plainTextIsParagraphsAndHtmlStaysLiteral() {
        assertEquals(listOf(ChatBlock.Paragraph("one"), ChatBlock.Paragraph("two\nthree")), parseChatMarkdown("one\n\ntwo\nthree"))
        assertEquals(listOf(ChatSpan.Text("<b>hi</b><script>x</script>")), parseChatInline("<b>hi</b><script>x</script>"))
    }

    @Test
    fun onlySafeLinksAndImagesNeverFetched() {
        assertTrue(isSafeChatUrl("https://a.dev") && isSafeChatUrl("http://a") && isSafeChatUrl("mailto:a@b.c"))
        assertFalse(isSafeChatUrl("javascript:alert(1)") || isSafeChatUrl("file:///c:/x") || isSafeChatUrl("data:text/html,x") || isSafeChatUrl("/rel") || isSafeChatUrl("https://"))
        assertEquals(listOf(ChatSpan.Text("click")), parseChatInline("[click](javascript:alert(1))"))
        assertEquals(listOf(ChatSpan.Text("alt")), parseChatInline("![alt](https://evil/x.png)"))
        assertEquals(listOf(ChatSpan.Link("m", "mailto:a@b.c")), parseChatInline("[m](mailto:a@b.c)"))
    }
}
