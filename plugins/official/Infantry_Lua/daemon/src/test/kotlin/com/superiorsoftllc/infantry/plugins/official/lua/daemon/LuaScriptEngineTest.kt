package com.superiorsoftllc.infantry.plugins.official.lua.daemon

import com.superiorsoftllc.infantry.plugins.official.scripting.daemon.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuaScriptEngineTest {
    private val engine = LuaScriptEngine()
    private val small = ScriptLimits(maxSteps = 200_000, timeoutMs = 1_500, maxAllocBytes = 8L * 1024 * 1024)

    @Test fun queuesActionsAndLogs() {
        val r = engine.run("""ih.log("hi "..input.ticket.id); ih.call("assign", {role="qa", n=2, tags={"a","b"}})""", mapOf("ticket" to mapOf("id" to "t1")), small)
        assertEquals(ScriptOutcome.OK, r.outcome, r.error)
        assertEquals(listOf("hi t1"), r.logs)
        assertEquals(QueuedAction("assign", mapOf("role" to "qa", "n" to 2L, "tags" to listOf("a", "b"))), r.actions.single())
    }

    @Test fun numericLookingStringIdsStayStrings() {
        val r = engine.run("""for _, id in ipairs({"39e65168", "1e5", "123"}) do ih.call("x", {id=id}) end; ih.call("y", {id=input.id, t=type(input.id)})""", mapOf("id" to "39e65168"), small)
        assertEquals(ScriptOutcome.OK, r.outcome, r.error)
        assertEquals(listOf("39e65168", "1e5", "123"), r.actions.take(3).map { it.args["id"] })
        assertEquals(mapOf("id" to "39e65168", "t" to "string"), r.actions[3].args)
    }

    @Test fun dangerousGlobalsAbsent() {
        val r = engine.run("""
            local bad = {}
            for _, n in ipairs({"io","luajava","require","load","dofile","loadfile","debug","package","loadstring"}) do if _G[n] ~= nil then bad[#bad+1]=n end end
            if os.execute or os.getenv or os.exit or os.remove then bad[#bad+1]="os.*" end
            ih.log(table.concat(bad, ","))
            ih.log(type(os.time()).." "..type(os.clock()))
        """, limits = small)
        assertEquals(ScriptOutcome.OK, r.outcome, r.error)
        assertEquals(listOf("", "number number"), r.logs)
    }

    @Test fun scriptErrorReported() {
        assertEquals(ScriptOutcome.ERROR, engine.run("error('boom')", limits = small).outcome)
        assertEquals(ScriptOutcome.ERROR, engine.run("os.execute('x')", limits = small).outcome)
    }

    @Test fun infiniteLoopHitsStepBudget() {
        assertEquals(ScriptOutcome.BUDGET_EXCEEDED, engine.run("while true do end", limits = small).outcome)
    }

    @Test fun slowScriptTimesOut() {
        val r = engine.run("while true do end", limits = ScriptLimits(maxSteps = Long.MAX_VALUE, timeoutMs = 300, maxAllocBytes = Long.MAX_VALUE))
        assertEquals(ScriptOutcome.TIMEOUT, r.outcome)
    }

    @Test fun bigAllocationExceeds() {
        val r = engine.run("local t = {} for i=1,1e9 do t[i] = {i, 'x'..i} end", limits = ScriptLimits(maxSteps = Long.MAX_VALUE, timeoutMs = 10_000, maxAllocBytes = 4L * 1024 * 1024))
        assertEquals(ScriptOutcome.ALLOC_EXCEEDED, r.outcome, r.error)
        assertEquals(ScriptOutcome.ALLOC_EXCEEDED, engine.run("local s = string.rep('x', 1e9)", limits = small).outcome)
    }

    @Test fun luaIsReachedThroughTheExtensionPoint() {
        val r = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "lua" to ScriptEngineWire.asEndpoint(engine)) })
        assertTrue(r.byExtension("lua")?.id == "lua")
        val res = r.default()!!.run("ih.call(\"go\", {n=input.n})", mapOf("n" to 3L), small, ScriptCaller("a", "qa"))
        assertEquals(ScriptOutcome.OK, res.outcome, res.error)
        assertEquals(QueuedAction("go", mapOf("n" to 3L)), res.actions.single())
    }
}
