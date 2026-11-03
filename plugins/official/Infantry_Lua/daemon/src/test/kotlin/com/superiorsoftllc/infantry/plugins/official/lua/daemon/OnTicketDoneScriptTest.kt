// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.lua.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolInfo
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.Tools
import com.superiorsoftllc.infantry.plugins.official.scripting.daemon.ScriptCaller
import com.superiorsoftllc.infantry.plugins.official.scripting.daemon.ScriptDispatcher
import com.superiorsoftllc.infantry.plugins.official.scripting.daemon.ScriptLimits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCRIPT-PROC-1: the shipped project script on-ticket-done, run for real through the Lua engine and dispatcher against fake board tools. */
class OnTicketDoneScriptTest {
    private class Fake(val todo: List<String>) : Tools {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override fun register(tool: DaemonTool) {}
        override fun catalog() = listOf("infantry_message", "infantry_session_remove", "infantry_board", "infantry_ticket_start", "infantry_ticket_comment")
            .map { ToolInfo(it, it, JsonObject(emptyMap())) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            calls += name to arguments
            return when (name) {
                "infantry_board" -> ToolResult("""{"board":["todo (${todo.size})",${todo.joinToString(",") { "\"  $it\"" }}]}""")
                else -> ToolResult("ok")
            }
        }
        fun named(n: String) = calls.filter { it.first == n }.map { it.second }
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    }

    private val aliases = mapOf("message" to "infantry_message", "close_session" to "infantry_session_remove", "board" to "infantry_board",
        "start" to "infantry_ticket_start", "comment" to "infantry_ticket_comment")
    private val invoker = ToolInvoker(Caller(owner = false, agent = true), "s1", "pm", "p", "script:on-ticket-done")
    private val src = File("../../Infantry_Scripting/project-scripts/on-ticket-done.lua").readText()
    private val args = mapOf<String, Any?>(
        "ticketId" to "ticket-aaaa1111", "title" to "T1", "branch" to "ticket/aaaa1111-t1", "tipSha" to "abc1234", "workerSession" to "sess-9",
        "devBranch" to "dev-x", "vcRole" to "version-control", "skipMarkers" to listOf("[HOLD]", "[PARKED]"),
    )

    private fun run(f: Fake, a: Map<String, Any?>, dry: Boolean = false) =
        ScriptDispatcher(f, { aliases }).run(LuaScriptEngine(), src, a, ScriptLimits(), ScriptCaller("s1", "pm"), invoker, dry)

    @Test fun `merge request, session close, next non-held ticket started, comment written`() {
        val f = Fake(listOf("11111111 | [HOLD] wait | x", "22222222 | [PARKED] later", "33333333 | real work | backend"))
        val r = run(f, args)
        assertTrue(r.ok, r.render("on-ticket-done"))
        val msg = f.named("infantry_message").single()
        assertEquals("version-control", f.str(msg, "to"))
        assertEquals("NEW request: merge ticket/aaaa1111-t1 tip abc1234 into dev-x --no-ff.", f.str(msg, "body")!!.substringBefore(" No gate"))
        assertEquals("sess-9", f.str(f.named("infantry_session_remove").single(), "sessionId"))
        assertEquals("33333333", f.str(f.named("infantry_ticket_start").single(), "ticketId"))
        val c = f.named("infantry_ticket_comment").single()
        assertEquals("ticket-aaaa1111", f.str(c, "ticketId"))
        assertTrue("version-control" in f.str(c, "body")!! && "Started the next todo ticket" in f.str(c, "body")!!)
    }

    @Test fun `empty board comments that and starts nothing`() {
        val f = Fake(listOf("11111111 | [HOLD] wait"))
        assertTrue(run(f, args).ok)
        assertTrue(f.named("infantry_ticket_start").isEmpty())
        assertTrue("Board is empty" in f.str(f.named("infantry_ticket_comment").single(), "body")!!)
    }

    @Test fun `dry run plans phase 1 only and executes nothing`() {
        val f = Fake(listOf("33333333 | work"))
        val r = run(f, args, dry = true)
        assertTrue(r.ok)
        assertEquals(listOf("infantry_message", "infantry_session_remove", "infantry_board"), r.records.map { it.tool })
        assertTrue(f.calls.isEmpty())
    }

    @Test fun `missing config is an error, no action`() {
        val f = Fake(emptyList())
        val r = run(f, args - "vcRole")
        assertEquals("ERROR", r.outcome)
        assertTrue(f.calls.isEmpty())
    }
}
