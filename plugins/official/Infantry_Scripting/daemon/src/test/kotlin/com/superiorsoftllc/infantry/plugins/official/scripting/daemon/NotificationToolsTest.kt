// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import com.superiorsoftllc.infantry.plugin.api.Notifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NOTIFY-CORE-1: `ih.call("notify", ...)` lands on the core bus as a script notification. */
class NotificationToolsTest {
    private val posted = mutableListOf<Notification>()
    private val bus = object : Notifications {
        override fun notify(title: String, body: String) { notify(Notification(title, body)) }
        override fun notify(notification: Notification): String { posted += notification; return "n-${posted.size}" }
    }
    private val tools = NotificationTools { bus }

    private fun call(json: String) = object : ToolCall {
        override val arguments: JsonObject = Json.parseToJsonElement(json).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String = "s1"
        override val clientProjectId: String = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
    }

    @Test fun `a script notification reaches the bus with every field`() {
        val r = tools.tool.call(call("""{"title":"Build failed","body":"see log","level":"error","dedupeKey":"build","sticky":true,
            "channels":["inApp","os"],"actions":[{"id":"retry","label":"Retry"},{"id":"open"}],"ticketId":"ticket-1"}"""))
        assertTrue(!r.isError, r.text)
        assertTrue(r.text.contains("\"n-1\""), r.text)
        val n = posted.single()
        assertEquals("Build failed", n.title)
        assertEquals(NotificationLevel.ERROR, n.level)
        assertEquals(NotificationTools.SOURCE, n.source)
        assertEquals("build", n.dedupeKey)
        assertTrue(n.sticky)
        assertEquals(listOf("inApp", "os"), n.channels)
        assertEquals(listOf("retry" to "Retry", "open" to "open"), n.actions.map { it.id to it.label })
        assertEquals("ticket-1", n.ticketId)
        assertEquals("s1", n.sessionId)
        assertEquals("p1", n.projectId)
    }

    @Test fun `a missing title or an unknown level is refused`() {
        assertTrue(tools.tool.call(call("""{"body":"x"}""")).isError)
        assertTrue(tools.tool.call(call("""{"title":"x","level":"loud"}""")).isError)
        assertTrue(posted.isEmpty())
        assertTrue(!tools.tool.call(call("""{"title":"x","level":"warn"}""")).isError)
        assertEquals(NotificationLevel.WARNING, posted.single().level)
    }

    @Test fun `notify is a built-in script alias`() {
        assertEquals(NotificationTools.NAME, ScriptDispatcher.BUILTIN_ALIASES["notify"])
    }
}
