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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BRIDGE-EVENTS-1: the shipped built-in script resume-after-restart (bound to daemon.started), run for real through the Lua engine
 * and the dispatcher. It replaces RESUME-NOTIFY-1's core RestartResume; these are that class's cases (RestartResumeTest), moved.
 */
class ResumeAfterRestartScriptTest {
    private class Fake(val refuse: Set<String> = emptySet()) : Tools {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override fun register(tool: DaemonTool) {}
        override fun catalog() = listOf("infantry_prompt", "infantry_ticket_comment", "infantry_message", "infantry_attention_raise")
            .map { ToolInfo(it, it, JsonObject(emptyMap())) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            calls += name to arguments
            return if (name == "infantry_prompt" && str(arguments, "sessionId") in refuse) ToolResult("session gone", true) else ToolResult("ok")
        }
        fun named(n: String) = calls.filter { it.first == n }.map { it.second }
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    }

    private val invoker = ToolInvoker(Caller(owner = true), null, null, null, "script:builtin:resume-after-restart")
    private val src = File("../../Infantry_Scripting/daemon/src/main/resources/builtin-scripts/resume-after-restart.lua").readText()

    private fun session(id: String, ticket: String? = null, starter: String? = null) = buildMap<String, Any?> {
        put("sessionId", id); put("role", "backend"); put("agentId", "agent-$id"); put("projectId", "p1")
        if (ticket != null) put("ticketId", ticket)
        if (starter != null) put("starter", starter)
    }

    private fun run(f: Fake, cut: List<Map<String, Any?>>, orphaned: List<Map<String, Any?>> = emptyList(), resume: Boolean = true, prompt: String = "") =
        ScriptDispatcher(f, { emptyMap() }).run(
            LuaScriptEngine(), src,
            mapOf("cutTurns" to cut, "orphaned" to orphaned, "orphanedIds" to orphaned.map { it["sessionId"] }, "resume" to resume, "prompt" to prompt),
            ScriptLimits(), ScriptCaller("", ""), invoker, false,
        )

    @Test fun `a cut session is prompted with the default prompt, its ticket is told, the owner gets one attention item`() {
        val f = Fake()
        val r = run(f, listOf(session("s1", ticket = "ticket-aaaa1111")))
        assertTrue(r.ok, r.render("resume-after-restart"))
        val p = f.named("infantry_prompt").single()
        assertEquals("s1", f.str(p, "sessionId"))
        assertEquals("The daemon restarted and your last turn was cut. Continue where you left off.", f.str(p, "prompt"))
        val c = f.named("infantry_ticket_comment").single()
        assertEquals("ticket-aaaa1111", f.str(c, "ticketId"))
        assertTrue("prompted to continue where it left off" in f.str(c, "body")!!, f.str(c, "body"))
        assertTrue(f.named("infantry_message").isEmpty())
        val att = f.named("infantry_attention_raise").single()
        assertEquals("session_resumed", f.str(att, "kind"))
        assertEquals("s1", f.str(att, "sessionId"))
        assertEquals("ticket-aaaa1111", f.str(att, "ticketId"))
        assertTrue(f.str(att, "detail")!!.startsWith("1 session(s) had their turn cut by a daemon restart; 1 resumed."), f.str(att, "detail"))
    }

    @Test fun `a session with no ticket tells its starter by mail, and the prompt setting replaces the default`() {
        val f = Fake()
        assertTrue(run(f, listOf(session("s2", starter = "pm-1")), prompt = "Go on.").ok)
        assertEquals("Go on.", f.str(f.named("infantry_prompt").single(), "prompt"))
        val m = f.named("infantry_message").single()
        assertEquals("pm-1", f.str(m, "to"))
        assertTrue("prompted to continue" in f.str(m, "body")!!)
        assertTrue(f.named("infantry_ticket_comment").isEmpty())
    }

    @Test fun `resume off prompts nobody but still tells and raises the summary`() {
        val f = Fake()
        assertTrue(run(f, listOf(session("s1", ticket = "ticket-aaaa1111"), session("s2", starter = "pm-1")), resume = false).ok)
        assertTrue(f.named("infantry_prompt").isEmpty())
        assertTrue("auto-resume is off" in f.str(f.named("infantry_ticket_comment").single(), "body")!!)
        assertTrue("auto-resume is off" in f.str(f.named("infantry_message").single(), "body")!!)
        val att = f.named("infantry_attention_raise").single()
        assertTrue(f.str(att, "detail")!!.startsWith("2 session(s) had their turn cut by a daemon restart; 0 resumed."))
        assertNull(f.str(att, "sessionId"), "several sessions: the item names none")
    }

    @Test fun `a refused prompt is reported as NOT resumed with the reason, the others still resume`() {
        val f = Fake(refuse = setOf("s1"))
        val r = run(f, listOf(session("s1", ticket = "ticket-aaaa1111"), session("s2", ticket = "ticket-bbbb2222")))
        assertTrue(r.ok, r.render("resume-after-restart"))
        assertEquals(listOf("s1", "s2"), f.named("infantry_prompt").map { f.str(it, "sessionId") })
        val byTicket = f.named("infantry_ticket_comment").associate { f.str(it, "ticketId") to f.str(it, "body")!! }
        assertTrue("could NOT be resumed (session gone)" in byTicket.getValue("ticket-aaaa1111"), byTicket.toString())
        assertTrue("prompted to continue" in byTicket.getValue("ticket-bbbb2222"))
        val detail = f.str(f.named("infantry_attention_raise").single(), "detail")!!
        assertTrue("1 resumed." in detail && "NOT resumed (session gone)" in detail, detail)
    }

    @Test fun `an orphaned session is named, never prompted`() {
        val f = Fake()
        assertTrue(run(f, emptyList(), orphaned = listOf(session("s3", ticket = "ticket-cccc3333"))).ok)
        assertTrue(f.named("infantry_prompt").isEmpty())
        assertTrue("process is still alive, so it was not prompted" in f.str(f.named("infantry_ticket_comment").single(), "body")!!)
        assertTrue("process still running" in f.str(f.named("infantry_attention_raise").single(), "detail")!!)
    }

    @Test fun `a clean start does nothing`() {
        val f = Fake()
        val r = run(f, emptyList())
        assertTrue(r.ok)
        assertTrue(f.calls.isEmpty())
        assertFalse(r.records.any { it.tool == "infantry_attention_raise" })
    }
}
