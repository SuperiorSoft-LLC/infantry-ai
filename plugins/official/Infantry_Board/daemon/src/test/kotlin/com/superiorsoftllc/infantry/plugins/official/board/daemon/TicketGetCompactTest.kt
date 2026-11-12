// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TICKET-COMPACT-1: the summary read keeps identity and routing and cuts the description; full is untouched. */
class TicketGetCompactTest {
    private fun ticket(desc: String) = TicketInfo(
        id = "t1", title = "T", description = desc, status = "todo", position = 0, createdAt = 0, updatedAt = 0,
        agentId = "role:backend", model = "m", branch = "b", epicId = "e",
    )

    @Test fun `compact cuts a long description and marks the cut`() {
        val c = TicketGetTool.compact(ticket("x".repeat(1000)), 300)
        val d = (c["description"] as JsonPrimitive).content
        assertTrue(d.startsWith("x".repeat(300)) && d.length < 400 && "cut at 300" in d)
        assertEquals("backend", (c["role"] as JsonPrimitive).content)
        // CI-FIX-IMAGES (e902f13f6): compact lists attachments (an empty array says none); the heavy fields stay out.
        assertTrue("images" in c)
        assertFalse("effectiveModel" in c)
    }

    @Test fun `compact keeps a short description and drops the RELATED block`() {
        val c = TicketGetTool.compact(ticket("<!-- RELATED (auto) -->\nr\n<!-- /RELATED -->\nbody"), 300)
        assertEquals("body", (c["description"] as JsonPrimitive).content)
    }

    @Test fun `full summary still carries the whole description`() {
        val s = TicketGetTool.summary(ticket("y".repeat(1000)))
        assertEquals(1000, (s["description"] as JsonPrimitive).content.length)
    }
}
