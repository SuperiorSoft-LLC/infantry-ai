package com.superiorsoftllc.agenthq.plugins.official.lua.daemon

import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.QueuedAction
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptCaller
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptEngine
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptLimits
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptOutcome
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptResult

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaThread
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import java.lang.management.ManagementFactory

/**
 * SCRIPT-2 (D17): LuaJ 3.0.1 in-process. Globals are base/string/table/math plus `os.time`/`os.clock` and `ih`;
 * no io, luajava, require, load, dofile, loadfile, debug. `ih.call`/`ih.log` only queue. No env vars or secrets reach it.
 */
class LuaScriptEngine : ScriptEngine {
    override val id = "lua"
    override val extensions = listOf("lua")
    override val binding = """
        Lua 5.2 (LuaJ), sandboxed: no io/require/load; os.time, os.clock, string, table, math only.
        ih.call("agenthq_ticket_move", {ticket="t1", status="done"})   queue any tool by registry name
        ih.move{ticket="t1", status="done"}                            same, by an alias (ih.<alias>{...} or ih.<toolname>{...}); args are ONE table of the tool's named arguments
        ih.exec{command={"prog","arg"}, cwd=".", timeoutSec=60}         run a process (needs scripting.exec.enabled); phase 2 input.results[i].json = {exitCode, durationMs, stdoutTail, stderrTail, timedOut}
        ih.log("text")                                                 add to the run log
        ih.again()                                                     after this run's actions execute, re-run the script with the results
        Nothing runs inside the script: actions are queued and executed in order after it ends; the first failure stops the run.
        input.args   the --args object;  input.phase  1, then 2.. after ih.again();  input.caller  {id, role}
        input.results (phase 2+)  list, one per executed action in order: {name, tool, ok, text, json}  (json = the tool's answer when it is JSON)
        Example: if not input.results then ih.move{ticket=input.args.ticket, status="done"}; ih.again()
                 else ih.comment{ticket=input.args.ticket, text="moved: "..input.results[1].text} end
    """.trimIndent()

    override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult {
        val actions = ArrayList<QueuedAction>()
        val logs = ArrayList<String>()
        val box = arrayOfNulls<ScriptResult>(1)
        val worker = Thread({ box[0] = runOnThisThread(source, input, limits, actions, logs) }, "lua-script").apply { isDaemon = true }
        worker.start()
        worker.join(limits.timeoutMs + 500)
        if (worker.isAlive) return ScriptResult(ScriptOutcome.TIMEOUT, emptyList(), emptyList(), "timeout")
        return box[0] ?: ScriptResult(ScriptOutcome.ERROR, error = "script thread died")
    }

    private fun runOnThisThread(
        source: String, input: Map<String, Any?>, limits: ScriptLimits,
        actions: MutableList<QueuedAction>, logs: MutableList<String>,
    ): ScriptResult {
        val bean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.takeIf { it.isThreadAllocatedMemorySupported }
        val started = System.nanoTime()
        try {
            val g = Globals()
            g.load(BaseLib()); g.load(org.luaj.vm2.lib.PackageLib()); g.load(TableLib()); g.load(StringLib()); g.load(JseMathLib()); g.load(DebugLib())
            LoadState.install(g); LuaC.install(g)
            val sethook = g.get("debug").get("sethook")
            for (k in listOf("debug", "dofile", "loadfile", "require", "load", "loadstring", "collectgarbage", "package")) g.set(k, LuaValue.NIL)
            val string = g.get("string") as LuaTable
            val rep = string.get("rep")
            string.set("rep", object : VarArgFunction() {
                override fun invoke(a: Varargs): Varargs {
                    val total = a.arg(1).tojstring().length.toLong() * a.arg(2).optlong(0)
                    if (total > limits.maxAllocBytes / 4) throw LuaError("ABORT:ALLOC")
                    return rep.invoke(a)
                }
            })
            val os = LuaTable()
            os.set("time", object : ZeroArgFunction() { override fun call() = LuaValue.valueOf((System.currentTimeMillis() / 1000).toDouble()) })
            os.set("clock", object : ZeroArgFunction() { override fun call() = LuaValue.valueOf((System.nanoTime() - started) / 1e9) })
            g.set("os", os)

            val ih = LuaTable()
            fun queue(name: String, args: LuaValue) {
                if (actions.size >= MAX_ACTIONS) throw LuaError("ih.call: too many actions")
                val a: Map<String, Any?> = if (args.isnil()) emptyMap()
                else (fromLua(args, 0) as? Map<*, *>)?.mapKeys { it.key.toString() } ?: throw LuaError("ih.call: args must be a table of named values")
                actions += QueuedAction(name, a)
            }
            ih.set("call", object : TwoArgFunction() {
                override fun call(name: LuaValue, args: LuaValue): LuaValue {
                    if (!name.isstring()) throw LuaError("ih.call: name must be a string")
                    queue(name.tojstring(), args)
                    return LuaValue.NIL
                }
            })
            // SCRIPT-4: any other name is an alias or tool name the dispatcher resolves from data: ih.move{...} == ih.call("move", {...}).
            val meta = LuaTable()
            meta.set(LuaValue.INDEX, object : TwoArgFunction() {
                override fun call(self: LuaValue, key: LuaValue): LuaValue {
                    if (!key.isstring()) return LuaValue.NIL
                    val name = key.tojstring()
                    return object : OneArgFunction() {
                        override fun call(args: LuaValue): LuaValue { queue(name, args); return LuaValue.NIL }
                    }
                }
            })
            ih.setmetatable(meta)
            ih.set("log", object : OneArgFunction() {
                override fun call(msg: LuaValue): LuaValue {
                    if (logs.size < MAX_LOGS) logs += msg.tojstring().take(MAX_LOG_LEN)
                    return LuaValue.NIL
                }
            })
            g.set("ih", ih)
            g.set("input", toLua(input, 0))

            val chunk = g.load(source, "script", g)
            val thread = LuaThread(g, chunk)
            var steps = 0L
            var hookThread = -1L
            var hookBase = 0L
            val deadline = started + limits.timeoutMs * 1_000_000L
            val hook = object : ZeroArgFunction() {
                override fun call(): LuaValue {
                    steps += HOOK_EVERY
                    if (steps > limits.maxSteps) throw LuaError("ABORT:BUDGET")
                    if (System.nanoTime() > deadline) throw LuaError("ABORT:TIMEOUT")
                    if (bean != null) {
                        // LuaJ runs the script on a coroutine thread of its own: measure that thread, baseline at its first hook.
                        val me = Thread.currentThread().id
                        val now = bean.getThreadAllocatedBytes(me)
                        if (hookThread != me) { hookThread = me; hookBase = now }
                        if (now - hookBase > limits.maxAllocBytes) throw LuaError("ABORT:ALLOC")
                    }
                    return LuaValue.NIL
                }
            }
            sethook.invoke(LuaValue.varargsOf(arrayOf(thread, hook, LuaValue.EMPTYSTRING, LuaValue.valueOf(HOOK_EVERY))))
            val r = thread.resume(LuaValue.NIL)
            if (r.arg1().toboolean()) return ScriptResult(ScriptOutcome.OK, actions, logs)
            val msg = r.arg(2).tojstring()
            return when {
                "ABORT:BUDGET" in msg -> ScriptResult(ScriptOutcome.BUDGET_EXCEEDED, actions, logs, "step budget exceeded")
                "ABORT:TIMEOUT" in msg -> ScriptResult(ScriptOutcome.TIMEOUT, actions, logs, "timeout")
                "ABORT:ALLOC" in msg -> ScriptResult(ScriptOutcome.ALLOC_EXCEEDED, actions, logs, "allocation cap exceeded")
                else -> ScriptResult(ScriptOutcome.ERROR, actions, logs, msg.take(500))
            }
        } catch (e: OutOfMemoryError) {
            return ScriptResult(ScriptOutcome.ALLOC_EXCEEDED, actions, logs, "out of memory")
        } catch (e: Throwable) {
            return ScriptResult(ScriptOutcome.ERROR, actions, logs, (e.message ?: e.javaClass.simpleName).take(500))
        }
    }

    private fun toLua(v: Any?, depth: Int): LuaValue {
        if (depth > MAX_DEPTH) return LuaValue.NIL
        return when (v) {
            null -> LuaValue.NIL
            is Boolean -> LuaValue.valueOf(v)
            is Number -> LuaValue.valueOf(v.toDouble())
            is Map<*, *> -> LuaTable().also { t -> v.forEach { (k, x) -> t.set(k.toString(), toLua(x, depth + 1)) } }
            is Iterable<*> -> LuaTable().also { t -> v.forEachIndexed { i, x -> t.set(i + 1, toLua(x, depth + 1)) } }
            else -> LuaValue.valueOf(v.toString())
        }
    }

    private fun fromLua(v: LuaValue, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw LuaError("ih.call: args nested too deeply")
        return when {
            v.isnil() -> null
            v.isboolean() -> v.toboolean()
            v.isnumber() -> v.todouble().let { if (it == Math.rint(it) && Math.abs(it) < 1e15) it.toLong() else it }
            v.isstring() -> v.tojstring()
            v.istable() -> {
                val t = v as LuaTable
                var count = 0
                var k: LuaValue = LuaValue.NIL
                while (true) { val n = t.next(k); k = n.arg1(); if (k.isnil()) break; count++ }
                if (t.length() > 0 && count == t.length()) (1..t.length()).map { fromLua(t.get(it), depth + 1) }
                else {
                    val m = LinkedHashMap<String, Any?>()
                    k = LuaValue.NIL
                    while (true) {
                        val n = t.next(k); k = n.arg1(); if (k.isnil()) break
                        m[k.tojstring()] = fromLua(n.arg(2), depth + 1)
                    }
                    m
                }
            }
            else -> throw LuaError("ih.call: unsupported value type ${v.typename()}")
        }
    }

    private companion object {
        const val HOOK_EVERY = 1000
        const val MAX_DEPTH = 16
        const val MAX_ACTIONS = 100
        const val MAX_LOGS = 200
        const val MAX_LOG_LEN = 2_000
    }
}
