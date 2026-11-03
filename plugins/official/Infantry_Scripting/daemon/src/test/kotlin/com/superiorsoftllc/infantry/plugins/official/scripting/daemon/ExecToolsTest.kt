package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SCRIPT-CMD-1: the process bridge against real child JVMs (the probe), no shell. */
class ExecToolsTest {
    private val root: File = Files.createTempDirectory("exec-root").toFile().canonicalFile
    private val settings = HashMap<String, String>().apply { put("scripting.exec.enabled", "true") }
    private val events = CopyOnWriteArrayList<Pair<String, String>>()
    private val logs = CopyOnWriteArrayList<String>()
    private val tools = ExecTools({ settings[it] }, { root }, { n, p -> events += n to p }, { logs += it })
    private val java = File(System.getProperty("java.home"), "bin/java").path
    private val cp = System.getProperty("java.class.path")
    private val probe = ExecProbe::class.java.name
    private val dollar = "$"

    private fun call(json: String, role: String = "backend") = object : ToolCall {
        override val arguments: JsonObject = Json.parseToJsonElement(json).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String = "sess-1"
        override val clientRole: String = role
        override val clientProjectId: String = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
    }

    private fun q(s: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))
    private fun argv(vararg a: String): String = (listOf(java, "-cp", cp, probe) + a).joinToString(",", "[", "]") { q(it) }
    private fun run(json: String) = tools.exec.call(call(json))
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun out(r: com.superiorsoftllc.infantry.daemon.api.ToolResult) = obj(r.text)["stdoutTail"]!!.jsonPrimitive.content.trim().replace("\r", "")

    @Test fun `argv runs directly and returns exit code and tails`() {
        val r = run("""{"command":${argv("args", "a b", "*", "${dollar}HOME")}}""")
        assertFalse(r.isError, r.text)
        assertEquals(0, obj(r.text)["exitCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("[a b]\n[*]\n[${dollar}HOME]", out(r))
        val f = obj(run("""{"command":${argv("exit", "3")}}""").text)
        assertEquals(3, f["exitCode"]!!.jsonPrimitive.content.toInt())
        assertTrue("failing" in f["stderrTail"]!!.jsonPrimitive.content)
        assertTrue(logs.any { "exit=3" in it && "role=backend" in it }, logs.toString())
    }

    @Test fun `a string is split on spaces and quotes with no shell expansion`() {
        assertEquals(listOf("a", "b c", "d e", "*", "${dollar}HOME", ""), CommandSplit.split("a \"b c\" 'd e' * ${dollar}HOME ''"))
        val cmd = "\"$java\" -cp \"$cp\" $probe args * ${dollar}HOME 'x y'"
        val r = run("""{"command":${q(cmd)}}""")
        assertEquals("[*]\n[${dollar}HOME]\n[x y]", out(r))
    }

    @Test fun `cwd env and stdin are honoured`() {
        val sub = File(root, "sub").apply { mkdirs() }
        assertEquals(sub.canonicalPath, out(run("""{"command":${argv("pwd")},"cwd":"sub"}""")))
        assertEquals(root.canonicalPath, out(run("""{"command":${argv("pwd")}}""")))
        assertEquals("bar", out(run("""{"command":${argv("env", "FOO_X")},"env":{"FOO_X":"bar"}}""")))
        assertEquals("HI", out(run("""{"command":${argv("stdin")},"stdin":"hi"}""")))
    }

    @Test fun `a timeout kills the whole process tree`() {
        val pidFile = File(root, "child.pid")
        val o = obj(run("""{"command":${argv("tree", pidFile.path)},"timeoutSec":3}""").text)
        assertTrue(o["timedOut"]!!.jsonPrimitive.content.toBoolean())
        val pid = pidFile.readText().trim().toLong()
        Thread.sleep(500)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "grandchild $pid survived")
    }

    @Test fun `refused when disabled and the text says how to enable it`() {
        settings.remove("scripting.exec.enabled")
        val r = run("""{"command":${argv("args")}}""")
        assertTrue(r.isError)
        assertTrue("scripting.exec.enabled" in r.text, r.text)
    }

    @Test fun `allow-list and roles refuse what they do not match`() {
        settings["scripting.exec.allow"] = """["re:.* $probe args.*", "nomatch-prefix"]"""
        assertFalse(run("""{"command":${argv("args")}}""").isError)
        val denied = run("""{"command":${argv("exit", "0")}}""")
        assertTrue(denied.isError && "allow-list" in denied.text, denied.text)
        settings["scripting.exec.allow"] = ""
        settings["scripting.exec.roles"] = "vc, qa"
        assertTrue(tools.exec.call(call("""{"command":${argv("args")}}""", "backend")).isError)
        assertFalse(tools.exec.call(call("""{"command":${argv("args")}}""", "VC")).isError)
    }

    @Test fun `background returns an id, status reads it and exec done fires`() {
        val id = obj(run("""{"command":${argv("exit", "5")},"background":true,"script":"s1"}""").text)["id"]!!.jsonPrimitive.content
        val deadline = System.currentTimeMillis() + 20_000
        while (events.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val (name, payload) = events.single()
        assertEquals("exec.done", name)
        val p = obj(payload)
        assertEquals(id, p["id"]!!.jsonPrimitive.content)
        assertEquals("5", p["exitCode"]!!.jsonPrimitive.content)
        assertEquals("s1", p["script"]!!.jsonPrimitive.content)
        assertEquals("sess-1", p["session"]!!.jsonPrimitive.content)
        val st = obj(tools.status.call(call("""{"id":"$id"}""")).text)
        assertEquals("done", st["state"]!!.jsonPrimitive.content)
        assertEquals("5", st["exitCode"]!!.jsonPrimitive.content)
    }
}
