// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** COMMENTS-TRUNC-1: full detail returns whole bodies; a cut carries an explicit marker. */
class CommentRenderTest {
    private fun c(body: String) = TicketComments.Comment("abcdef123456", "t", TicketComments.Author.owner, "note", body, "open", 0, null, null, emptyList())
    private val big = (1..500).joinToString("\n") { "line $it xxxxxxxxxx" }

    @Test fun `compact is the first line only`() {
        assertTrue(CommentsWiring.render(c(big), "s", false).endsWith(": line 1 xxxxxxxxxx"))
    }

    @Test fun `full returns the whole body, 5000+ chars, no marker`() {
        val out = CommentsWiring.render(c(big), "s", true)
        assertTrue(out.endsWith(big)); assertFalse("[cut at" in out)
    }

    @Test fun `maxChars cuts with a marker`() {
        val out = CommentsWiring.render(c(big), "s", true, 100)
        assertTrue("[cut at 100 chars of ${big.length}" in out)
        assertEquals(100, out.substringAfter(": ").substringBefore("\n[cut").length)
    }
}
