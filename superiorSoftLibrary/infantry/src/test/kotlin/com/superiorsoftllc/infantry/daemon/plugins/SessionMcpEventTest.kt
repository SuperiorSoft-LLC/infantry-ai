// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CLI-PTY-3: `session.mcp` fires on a change of a session's MCP link, once per change, and is a documented daemon event. */
class SessionMcpEventTest {
    private val published = mutableListOf<Pair<String, String>>()
    private val publisher = DaemonEventPublisher { n, p -> published += n to p }
    private fun mcpEvents() = published.filter { it.first == "session.mcp" }.map { Json.parseToJsonElement(it.second).jsonObject }

    @Test fun `each change fires once, a repeat or a never-reported session fires nothing`() {
        publisher.sessionMcp("s1", null, null, "backend", "p1")
        publisher.sessionMcp("s1", "connected", 10L, "backend", "p1")
        publisher.sessionMcp("s1", "connected", 10L, "backend", "p1")
        publisher.sessionMcp("s1", "disconnected", 20L, "backend", "p1")
        publisher.sessionMcp("s1", "reconnecting", 30L, "backend", "p1")
        publisher.sessionMcp("s1", null, 40L, "backend", "p1")
        val e = mcpEvents()
        assertEquals(listOf("connected", "disconnected", "reconnecting", "none"), e.map { it["state"]!!.jsonPrimitive.content })
        assertEquals("connected", e[1]["previous"]!!.jsonPrimitive.content)
        assertEquals("20", e[1]["at"]!!.jsonPrimitive.content)
        assertEquals("p1", e[1]["projectId"]!!.jsonPrimitive.content)
        assertTrue("previous" !in e[0], "the first report has no previous state")
    }

    @Test fun `session mcp is in the documented event list`() {
        assertTrue("session.mcp" in NamedEventBus.DAEMON_EVENTS)
    }
}
