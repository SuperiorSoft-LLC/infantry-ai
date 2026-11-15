// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.Sessions
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ANNOUNCE-ALL-1: permission, project scope, live-only reach and the sent/skipped count. */
class AnnounceToolTest {
    private fun s(id: String, project: String?, role: String?, state: String = "IDLE", global: Boolean = false) =
        SessionInfo(id, project, role, null, global, 0, state, false, null, 1)

    private val all = listOf(
        s("pm1", "p1", "pm"), s("w1", "p1", "fullstack"), s("w2", "p1", "qa", "BUSY"),
        s("old", "p1", "qa", "FAILED"), s("asst", "p1", "assistant", global = true), s("other", "p2", "fullstack"),
    )
    private val sessions = object : Sessions {
        override fun list() = all
        override fun get(id: String) = all.firstOrNull { it.id == id }
        override fun spawn(provider: String, model: String, role: String, projectId: String): SessionInfo = error("unused")
        override fun resume(id: String, prompt: String): SessionInfo = error("unused")
        override fun restart(id: String) = error("unused")
    }

    private class Call(val args: JsonObject, override val clientSessionId: String?) : ToolCall {
        val sent = mutableListOf<String>()
        override val arguments get() = args
        override val caller: Caller get() = error("unused")
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame {
            sent += Json.parseToJsonElement(data).jsonObject["to"].toString().trim('"'); return Frame("mail", "{}")
        }
    }

    private fun args(j: String) = Json.parseToJsonElement(j).jsonObject
    private val tool = AnnounceTool(sessions, null)

    @Test fun pmReachesLiveSessionsOfItsProjectOnly() {
        val call = Call(args("""{"body":"hello"}"""), "pm1")
        val r = tool.call(call)
        assertEquals("sent 2, skipped 1", r.text)
        assertEquals(listOf("w1", "w2"), call.sent)
    }

    @Test fun ownerNeedsAProjectAndIsAllowed() {
        val call = Call(args("""{"body":"hi","project":"p1"}"""), null)
        assertEquals("sent 3, skipped 1", tool.call(call).text)
        assertTrue(tool.call(Call(args("""{"body":"hi"}"""), null)).isError)
    }

    @Test fun otherRolesAreRefusedUnlessTheSettingWidensIt() {
        val r = tool.call(Call(args("""{"body":"x"}"""), "w1"))
        assertTrue(r.isError && "may not announce" in r.text)
        assertEquals(setOf("owner", "pm", "fullstack"), AnnounceTool.allowedRoles("""["owner","pm","fullstack"]"""))
    }
}
