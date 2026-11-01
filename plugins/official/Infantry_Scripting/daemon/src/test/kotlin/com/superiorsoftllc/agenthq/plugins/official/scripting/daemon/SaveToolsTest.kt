package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.files.DirectoryProjectFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SCRIPT-SAVE-1: save -> run in the same daemon, refusal on a parse error, delete refused while hooked. */
class SaveToolsTest {
    private class Lua : ScriptEngine {
        override val id = "lua"
        override val extensions = listOf("lua")
        override fun check(source: String) = if ("BAD" in source) "unexpected symbol near BAD" else null
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller) =
            ScriptResult(ScriptOutcome.OK, emptyList(), listOf("ran:$source"))
    }

    private val files = DirectoryProjectFiles(createTempDirectory("save").toFile())
    private val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "lua" to ScriptEngineWire.asEndpoint(Lua())) })
    private val store = ScriptStore({ files }, { engines.extensions() })
    private val hooks = HookStore { files }
    private val save = SaveTools(store, hooks, engines)

    private fun call(args: String) = object : ToolCall {
        override val arguments = Json.parseToJsonElement(args).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String? = "sess-1"
        override val clientRole: String? = "qa"
        override val clientProjectId: String? = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("no frames")
    }

    @Test fun `save then run in the same daemon, overwrite says updated, list shows who saved it`() {
        val a = save.save.call(call("""{"name":"hello","source":"x=1"}"""))
        assertFalse(a.isError, a.text); assertTrue(a.text.startsWith("created script hello.lua (3 bytes)"), a.text)
        assertTrue(save.save.call(call("""{"name":"hello","source":"x=22"}""")).text.startsWith("updated"))
        val tools = ScriptTools(ScriptDispatcherTest.FakeTools(), engines, store, ScriptingLimits { null }, { emptyMap() }, { 3 })
        val ran = tools.run.call(call("""{"name":"hello"}"""))
        assertFalse(ran.isError, ran.text); assertTrue("ran:x=22" in ran.text, ran.text)
        val list = save.list.call(call("{}")).text
        assertTrue("hello" in list && "engine=lua" in list && "sess-1 (qa)" in list && "hooks: none" in list, list)
    }

    @Test fun `save is refused on a parse error and writes nothing`() {
        val r = save.save.call(call("""{"name":"bad","source":"BAD"}"""))
        assertTrue(r.isError && "does not parse: unexpected symbol" in r.text, r.text)
        assertEquals(emptyList(), store.names("p1"))
        assertTrue(save.save.call(call("""{"name":"../x","source":"x"}""")).isError)
    }

    @Test fun `delete is refused while a hook uses the script and works after the hook is gone`() {
        save.save.call(call("""{"name":"h","source":"x"}"""))
        val ht = HookTools(hooks, store)
        assertFalse(ht.handle(call("""{"action":"add","on":"ticket.commented","script":"h","id":"hk1"}""")).isError)
        assertTrue(save.list.call(call("{}")).text.contains("hooks: hk1"))
        val d = save.delete.call(call("""{"name":"h"}"""))
        assertTrue(d.isError && "hk1" in d.text, d.text)
        ht.handle(call("""{"action":"remove","id":"hk1"}"""))
        assertFalse(save.delete.call(call("""{"name":"h"}""")).isError)
        assertTrue(save.delete.call(call("""{"name":"h"}""")).isError)
    }
}
