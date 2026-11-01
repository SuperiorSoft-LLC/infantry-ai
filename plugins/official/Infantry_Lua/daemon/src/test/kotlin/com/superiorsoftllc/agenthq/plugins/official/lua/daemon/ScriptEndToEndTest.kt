package com.superiorsoftllc.agenthq.plugins.official.lua.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolInfo
import com.superiorsoftllc.agenthq.daemon.api.ToolInvoker
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.Tools
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptCaller
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptDispatcher
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptLimits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCRIPT-4: a Lua script moves a test ticket and comments on it through a fake in-process ticket tool pair, results flowing back. */
class ScriptEndToEndTest {
    private class Board : Tools {
        val status = mutableMapOf("t1" to "in_progress")
        val comments = mutableListOf<String>()
        override fun register(tool: DaemonTool) {}
        override fun catalog() = listOf("fake_ticket_move", "fake_ticket_comment").map { ToolInfo(it, it, JsonObject(emptyMap())) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            val t = (arguments["ticket"] as JsonPrimitive).content
            return when (name) {
                "fake_ticket_move" -> {
                    val from = status[t]
                    status[t] = (arguments["status"] as JsonPrimitive).content
                    ToolResult("""{"ticket":"$t","from":"$from","to":"${status[t]}"}""")
                }
                "fake_ticket_comment" -> { comments += "$t:${(arguments["text"] as JsonPrimitive).contentOrNull}"; ToolResult("commented") }
                else -> ToolResult("unknown", true)
            }
        }
    }

    private val aliases = mapOf("move" to "fake_ticket_move", "comment" to "fake_ticket_comment")
    private val invoker = ToolInvoker(Caller(owner = false, agent = true), "s1", "qa", "p", "script:done")

    @Test fun `a lua script moves a ticket then comments the result using an alias and again`() {
        val board = Board()
        val src = """
            if not input.results then
              ih.move{ticket=input.args.ticket, status="done"}
              ih.again()
            else
              ih.comment{ticket=input.args.ticket, text="was "..input.results[1].json.from.." now "..input.results[1].json.to}
              ih.log("commented")
            end
        """.trimIndent()
        val r = ScriptDispatcher(board, { aliases }).run(LuaScriptEngine(), src, mapOf("ticket" to "t1"), ScriptLimits(), ScriptCaller("s1", "qa"), invoker, false)
        assertTrue(r.ok, r.render("done"))
        assertEquals("done", board.status["t1"])
        assertEquals(listOf("t1:was in_progress now done"), board.comments)
        assertEquals(2, r.phases)
        assertTrue("[phase 2] commented" in r.logs)
    }

    @Test fun `ih call reaches a tool by registry name and dry run plans without touching the board`() {
        val board = Board()
        val src = """ih.call("fake_ticket_move", {ticket="t1", status="todo"}); ih.comment{ticket="t1", text="x"}"""
        val dry = ScriptDispatcher(board, { aliases }).run(LuaScriptEngine(), src, emptyMap(), ScriptLimits(), ScriptCaller(), invoker, true)
        assertEquals(listOf("fake_ticket_move", "fake_ticket_comment"), dry.records.map { it.tool })
        assertEquals("in_progress", board.status["t1"])
        assertTrue(board.comments.isEmpty())
        val live = ScriptDispatcher(board, { aliases }).run(LuaScriptEngine(), src, emptyMap(), ScriptLimits(), ScriptCaller(), invoker, false)
        assertTrue(live.ok)
        assertEquals("todo", board.status["t1"])
    }

    @Test fun `a script error reports the engine outcome and runs no action`() {
        val board = Board()
        val r = ScriptDispatcher(board, { aliases }).run(LuaScriptEngine(), "ih.move{ticket='t1', status='done'}; error('boom')", emptyMap(), ScriptLimits(), ScriptCaller(), invoker, false)
        assertEquals("ERROR", r.outcome)
        assertEquals("in_progress", board.status["t1"])
    }
}
