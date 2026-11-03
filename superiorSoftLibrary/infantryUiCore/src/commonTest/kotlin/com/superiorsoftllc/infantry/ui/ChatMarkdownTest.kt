// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.ChatSpan
import com.superiorsoftllc.infantry.ui.model.parseChatMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals

/** Chat reply formatting (CHATFMT): pure parsing of an assistant reply into display blocks. */
class ChatMarkdownTest {
    @Test
    fun chatMarkdownSplitsFencedCodeBlock() {
        val reply = "Here is the fix:\n```kotlin\nval x = 1\nprintln(x)\n```\n"

        assertEquals(
            listOf(
                ChatBlock.Paragraph("Here is the fix:"),
                ChatBlock.Code("kotlin", "val x = 1\nprintln(x)"),
            ),
            parseChatMarkdown(reply),
        )
    }

    @Test
    fun chatMarkdownParsesBulletList() {
        val reply = "Intro\n- a\n- b\n"

        assertEquals(
            listOf(
                ChatBlock.Paragraph("Intro"),
                ChatBlock.BulletList(listOf("a", "b")),
            ),
            parseChatMarkdown(reply),
        )
    }

    @Test
    fun chatMarkdownParsesLink() {
        val reply = "see [docs](https://x.dev/a) here"

        assertEquals(
            listOf(
                ChatBlock.Paragraph(
                    "see [docs](https://x.dev/a) here",
                    listOf(
                        ChatSpan.Text("see "),
                        ChatSpan.Link("docs", "https://x.dev/a"),
                        ChatSpan.Text(" here"),
                    ),
                ),
            ),
            parseChatMarkdown(reply),
        )
    }

    @Test
    fun chatMarkdownParsesBoldAndInlineCode() {
        val reply = "use **fast** `foo()` now"

        assertEquals(
            listOf(
                ChatBlock.Paragraph(
                    "use **fast** `foo()` now",
                    listOf(
                        ChatSpan.Text("use "),
                        ChatSpan.Bold("fast"),
                        ChatSpan.Text(" "),
                        ChatSpan.InlineCode("foo()"),
                        ChatSpan.Text(" now"),
                    ),
                ),
            ),
            parseChatMarkdown(reply),
        )
    }

    @Test
    fun chatMarkdownParsesHeadings() {
        assertEquals(
            listOf(
                ChatBlock.Heading(1, "One"),
                ChatBlock.Heading(2, "Two"),
                ChatBlock.Heading(3, "Three"),
            ),
            parseChatMarkdown("# One\n## Two\n### Three"),
        )
        // A fourth level and beyond clamp to level 3.
        assertEquals(listOf(ChatBlock.Heading(3, "Deep")), parseChatMarkdown("#### Deep"))
        // A '#' with no following space is plain paragraph text, not a heading.
        assertEquals(listOf(ChatBlock.Paragraph("#nospace")), parseChatMarkdown("#nospace"))
        // A '#' line inside a fenced code block stays code.
        assertEquals(listOf(ChatBlock.Code("", "# not a heading")), parseChatMarkdown("```\n# not a heading\n```"))
    }
}
