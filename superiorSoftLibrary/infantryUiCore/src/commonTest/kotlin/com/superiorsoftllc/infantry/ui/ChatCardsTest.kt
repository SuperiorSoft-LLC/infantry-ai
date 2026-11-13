// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.BuiltInChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.CardKeys
import com.superiorsoftllc.infantry.ui.model.ChatBlock
import com.superiorsoftllc.infantry.ui.model.ChatMarkdownEngine
import com.superiorsoftllc.infantry.ui.model.ChatMarkdownFeature
import com.superiorsoftllc.infantry.ui.model.parseChatMarkdown
import com.superiorsoftllc.infantry.ui.model.parseChatMarkdownSafely
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** CARD-1: a `card:<type>` fence becomes a card block; anything not a valid card stays exactly what it was. */
class ChatCardsTest {
    private fun parse(text: String, streaming: Boolean = false) = parseChatMarkdownSafely(BuiltInChatMarkdownEngine, text, streaming)

    @Test fun validFenceBecomesCard() {
        val b = parse("Hi\n\n```card:demo id=n1 op=append\n{\"items\":[1]}\n```\n").last()
        val c = assertIs<ChatBlock.Custom>(b)
        assertEquals("card:demo", c.type)
        assertEquals("{\"items\":[1]}", c.data[CardKeys.BODY])
        assertEquals("n1", c.data[CardKeys.ID])
        assertEquals("append", c.data[CardKeys.OP])
        assertEquals("complete", c.data[CardKeys.STATE])
        assertEquals("card:demo id=n1 op=append", c.data[CardKeys.LANGUAGE])
    }

    @Test fun defaultsAreSetAndNoId() {
        val c = assertIs<ChatBlock.Custom>(parse("```card:demo\n{}\n```").single())
        assertEquals("set", c.data[CardKeys.OP]); assertEquals("", c.data[CardKeys.ID])
    }

    @Test fun invalidStaysTheCodeBlock() {
        for (t in listOf(
            "```card:demo\nnot json\n```", "```card:demo\n[1,2]\n```", "```card:demo op=nope\n{}\n```",
            "```card:\n{}\n```", "```card:demo junk\n{}\n```",
        )) assertEquals(parseChatMarkdown(t), parse(t), t)
    }

    @Test fun streamingOpenFenceStaysCode() {
        val t = "```card:demo\n{\"items\":["
        assertEquals(parseChatMarkdown(t, streaming = true), parse(t, streaming = true))
    }

    @Test fun normalMarkdownUnchanged() {
        val t = "# H\n\ntext **b**\n\n- a\n\n```kotlin\nval x = 1\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(parseChatMarkdown(t), parse(t))
    }

    @Test fun runsAfterAnyEngine() {
        val engine = object : ChatMarkdownEngine {
            override val id = "x"; override val label = "x"
            override fun parse(text: String, streaming: Boolean) = listOf(ChatBlock.Code("card:demo", "{\"a\":1}"))
            override fun supports(feature: ChatMarkdownFeature) = true
        }
        assertIs<ChatBlock.Custom>(parseChatMarkdownSafely(engine, "").single())
    }
}
