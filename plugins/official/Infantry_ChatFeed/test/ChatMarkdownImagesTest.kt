// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chatfeed
import kotlin.test.Test
import kotlin.test.assertEquals

/** CHAT-IMAGE-1: `![caption](path)` shows inline only for an image-only line of an existing image file; all else stays text. */
class ChatMarkdownImagesTest {
    @Test fun `an existing image line is lifted out and the rest stays`() {
        val (text, paths) = ChatMarkdownImages.split("before\n![shot](C:/p/a.png)\nafter") { true }
        assertEquals("before\nafter", text); assertEquals(listOf("C:/p/a.png"), paths)
    }
    @Test fun `a missing file, a non image type, a url and inline text stay as they are`() {
        val src = "![a](x.png)\n![b](notes.txt)\n![c](https://h/x.png)\nsee ![d](y.png) here"
        val (text, paths) = ChatMarkdownImages.split(src) { false }
        assertEquals(src, text); assertEquals(emptyList(), paths)
        assertEquals(emptyList(), ChatMarkdownImages.split("![b](notes.txt)") { true }.second)
    }
}
