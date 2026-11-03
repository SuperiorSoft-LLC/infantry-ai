package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.ServiceEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCRIPT-2: the registry over published endpoints, with fake in-test engines (no Lua, no core). */
class ScriptEnginesTest {
    private class Fake(override val id: String, override val extensions: List<String>) : ScriptEngine {
        var lastCaller: ScriptCaller? = null
        var lastLimits: ScriptLimits? = null
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult {
            lastCaller = caller; lastLimits = limits
            return ScriptResult(ScriptOutcome.OK, listOf(QueuedAction("echo", mapOf("src" to source, "n" to (input["n"] ?: 0L), "tags" to listOf("a", "b")))), listOf("ran $id"))
        }
    }

    private fun registry(vararg engines: Fake, default: String = "lua"): ScriptEngines {
        val map = engines.associate { ScriptEngineWire.PREFIX + it.id to ScriptEngineWire.asEndpoint(it) } + mapOf("other.thing/x" to ServiceEndpoint { _, _ -> "" })
        return ScriptEngines({ map }, { default })
    }

    @Test fun `finds engines by id and by extension and lists ids`() {
        val r = registry(Fake("lua", listOf("lua")), Fake("py", listOf("py", "PYW")))
        assertEquals(setOf("lua", "py"), r.ids())
        assertEquals("py", r.byId("py")?.id)
        assertEquals("py", r.byExtension(".pyw")?.id)
        assertNull(r.byId("js"))
        assertNull(r.byExtension("js"))
        assertEquals("lua", r.default()?.id)
        assertNull(registry(Fake("py", listOf("py")), default = "nope").default())
    }

    @Test fun `a run crosses the wire with source, input, limits and caller and returns actions and logs`() {
        val f = Fake("lua", listOf("lua"))
        val limits = ScriptLimits(maxSteps = 10, timeoutMs = 20, maxAllocBytes = 70_000)
        val res = registry(f).byId("lua")!!.run("x=1", mapOf("n" to 7L), limits, ScriptCaller("a1", "qa"))
        assertEquals(ScriptOutcome.OK, res.outcome)
        assertEquals(listOf("ran lua"), res.logs)
        assertEquals(QueuedAction("echo", mapOf("src" to "x=1", "n" to 7L, "tags" to listOf("a", "b"))), res.actions.single())
        assertEquals(ScriptCaller("a1", "qa"), f.lastCaller)
        assertEquals(limits, f.lastLimits)
    }

    @Test fun `an engine that throws or answers garbage becomes an error result or is skipped`() {
        val bad = ServiceEndpoint { op, _ -> if (op == "describe") """{"id":"bad","extensions":["bad"]}""" else error("kaput") }
        val garbage = ServiceEndpoint { _, _ -> "not json" }
        val r = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "bad" to bad, ScriptEngineWire.PREFIX + "g" to garbage) })
        assertEquals(setOf("bad"), r.ids())
        val res = r.byId("bad")!!.run("x")
        assertEquals(ScriptOutcome.ERROR, res.outcome)
        assertTrue(res.error!!.contains("kaput"))
    }

    @Test fun `limits come from config with defaults and out of range values fall back`() {
        val cfg = mapOf("scripting.maxSteps" to "5000", "scripting.timeoutMs" to "1", "scripting.maxAllocBytes" to "junk")
        val l = ScriptingLimits { cfg[it] }.current()
        assertEquals(5000L, l.maxSteps)
        assertEquals(ScriptLimits().timeoutMs, l.timeoutMs)
        assertEquals(ScriptLimits().maxAllocBytes, l.maxAllocBytes)
    }
}
