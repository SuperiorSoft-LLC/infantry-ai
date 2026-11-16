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

/** DONE-KILL-FIRST-1: on-ticket-done-v2 kills the worker first and requests the merge only when the board reads the worker gone. */
class OnTicketDoneV2ScriptTest {
    /** The done lane reads the worker "busy" until the kill ran, then "gone" (unless [stubborn]); the kill answer is [killOk]. */
    private class Fake(val todo: List<String>, val killOk: Boolean = true, val stubborn: Boolean = false) : Tools {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        private var killed = false
        override fun register(tool: DaemonTool) {}
        override fun catalog() = listOf("infantry_message", "infantry_session_remove", "infantry_board", "infantry_ticket_start", "infantry_ticket_comment")
            .map { ToolInfo(it, it, JsonObject(emptyMap())) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            calls += name to arguments
            return when (name) {
                "infantry_session_remove" -> { if (killOk && !stubborn) killed = true; if (killOk) ToolResult("""{"removed":true}""") else ToolResult("refused: still running", isError = true) }
                "infantry_board" ->
                    if (str(arguments, "columns") == "done") {
                        val w = if (killed) "worker 7e00da53 gone" else "backend 7e00da53 busy"
                        ToolResult("""{"board":["done (1)","  aaaa1111 | T1 | $w | ticket/aaaa1111-t1@no commit"]}""")
                    } else ToolResult("""{"board":["todo (${todo.size})",${todo.joinToString(",") { "\"  $it\"" }}]}""")
                else -> ToolResult("ok")
            }
        }
        fun named(n: String) = calls.filter { it.first == n }.map { it.second }
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun order() = calls.map { it.first }
    }

    private val aliases = mapOf("message" to "infantry_message", "close_session" to "infantry_session_remove", "board" to "infantry_board",
        "start" to "infantry_ticket_start", "comment" to "infantry_ticket_comment")
    private val invoker = ToolInvoker(Caller(owner = false, agent = true), "s1", "pm", "p", "script:on-ticket-done-v2")
    private val src = File("../../Infantry_Scripting/project-scripts/on-ticket-done-v2.lua").readText()
    private val args = mapOf<String, Any?>(
        "ticketId" to "ticket-aaaa1111", "title" to "T1", "branch" to "ticket/aaaa1111-t1", "tipSha" to "abc1234", "workerSession" to "sess-9",
        "devBranch" to "dev-x", "vcRole" to "version-control", "skipMarkers" to listOf("[HOLD]"),
    )

    private fun run(f: Fake, a: Map<String, Any?>) =
        ScriptDispatcher(f, { aliases }).run(LuaScriptEngine(), src, a, ScriptLimits(), ScriptCaller("s1", "pm"), invoker, false)

    @Test fun `the worker is killed with force before the merge request, then the next ticket starts`() {
        val f = Fake(listOf("33333333 | real work | backend"))
        val r = run(f, args)
        assertTrue(r.ok, r.render("on-ticket-done-v2"))
        val kill = f.named("infantry_session_remove").single()
        assertEquals("sess-9", f.str(kill, "sessionId"))
        assertEquals("true", f.str(kill, "force"))
        assertTrue(f.order().indexOf("infantry_session_remove") < f.order().indexOf("infantry_message"), "kill must precede the merge request")
        val msg = f.str(f.named("infantry_message").single(), "body")!!
        assertTrue("CURRENT HEAD" in msg && "ticket/aaaa1111-t1" in msg, msg)
        assertEquals("33333333", f.str(f.named("infantry_ticket_start").single(), "ticketId"))
    }

    @Test fun `no worker named on the event, the worker on the board line is killed first`() {
        val f = Fake(emptyList())
        val r = run(f, args - "workerSession")
        assertTrue(r.ok, r.render("on-ticket-done-v2"))
        assertEquals("7e00da53", f.str(f.named("infantry_session_remove").single(), "sessionId"))
        assertTrue(f.order().indexOf("infantry_session_remove") < f.order().indexOf("infantry_message"))
    }

    @Test fun `a failed kill sends no merge request and starts nothing, the failure is commented`() {
        val f = Fake(listOf("33333333 | real work"), killOk = false)
        assertTrue(run(f, args).ok)
        assertTrue(f.named("infantry_message").isEmpty())
        assertTrue(f.named("infantry_ticket_start").isEmpty())
        assertTrue("MERGE NOT REQUESTED" in f.str(f.named("infantry_ticket_comment").single(), "body")!!)
    }

    @Test fun `a kill that leaves the worker live on the board sends no merge request`() {
        val f = Fake(listOf("33333333 | real work"), stubborn = true)
        assertTrue(run(f, args).ok)
        assertTrue(f.named("infantry_message").isEmpty())
        assertTrue("still shows worker" in f.str(f.named("infantry_ticket_comment").single(), "body")!!)
    }
}
