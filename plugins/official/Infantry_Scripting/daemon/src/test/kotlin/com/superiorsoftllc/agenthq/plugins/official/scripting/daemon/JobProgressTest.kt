package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCRIPT-JOB-1: marker parsing, the data regexes, done/fail ending the bar, the rate limit, and the job list / newLines / state. */
class JobProgressTest {
    private val sent = CopyOnWriteArrayList<JsonObject>()
    private var now = 0L
    private fun jp(steps: List<String> = emptyList(), markers: Boolean = true, pct: String? = null, step: String? = null) =
        JobProgress("build", steps, markers, pct, step, { sent += it }, { now })
    private fun modes() = sent.map { it["mode"]!!.jsonPrimitive.content }

    @Test fun `marker lines drive plan step percent count and done`() {
        val p = jp()
        p.line("##infantry plan a|b|c")
        p.line("noise")
        now += 2000; p.line("##infantry step a")
        now += 2000; p.line("  ##infantry progress 40% compile")
        now += 2000; p.line("##infantry count 3 10 files")
        p.line("##infantry done")
        assertEquals(listOf("status", "plan", "step", "percent", "count", "done"), modes())
        assertEquals(3, sent[1]["steps"]!!.jsonArray.size)
        assertEquals("40.0", sent[3]["percent"]!!.jsonPrimitive.content)
        assertEquals("3", sent[4]["done"]!!.jsonPrimitive.content); assertEquals("10", sent[4]["total"]!!.jsonPrimitive.content)
    }

    @Test fun `markers off ignores marker lines`() {
        val p = jp(markers = false)
        p.line("##infantry progress 50")
        assertEquals(listOf("status"), modes())
    }

    @Test fun `stepRegex plans once from N and steps, percentRegex reports the number`() {
        val p = jp(pct = """(\d+(?:\.\d+)?)%""", step = """step (\d+)/(\d+): (.*)""")
        p.line("step 1/4: compile")
        now += 2000; p.line("step 2/4: test")
        now += 2000; p.line("downloading 55.5% of x")
        assertEquals(listOf("status", "plan", "step", "percent"), modes())
        assertEquals("4", sent[1]["count"]!!.jsonPrimitive.content)
        assertEquals("compile", sent[2]["step"]!!.jsonPrimitive.content)
        assertEquals(55.5, p.percentNow)
    }

    @Test fun `exit code ends the bar - zero is done, non-zero is fail with the reason, and only once`() {
        val ok = jp(); ok.finish(0, null); ok.finish(1, "late")
        assertEquals(listOf("status", "done"), modes())
        sent.clear()
        val bad = jp(); bad.finish(3, null)
        assertEquals("fail", modes().last()); assertEquals("exit code 3", sent.last()["label"]!!.jsonPrimitive.content)
        sent.clear()
        val m = jp(); m.line("##infantry fail disk full"); m.line("##infantry progress 90"); m.finish(0, null)
        assertEquals("disk full", sent.last()["label"]!!.jsonPrimitive.content); assertEquals(listOf("status", "fail"), modes())
    }

    @Test fun `updates are rate limited to one per second, the latest is kept and flushed at the end`() {
        val p = jp()
        repeat(100) { i -> now += 5; p.line("##infantry progress $i") }
        assertTrue(modes().count { it == "percent" } <= 1, modes().toString())
        now += 1000; p.line("##infantry progress 100")
        val before = sent.size
        p.line("##infantry progress 99.5")
        assertEquals(before, sent.size, "inside the gap: kept, not sent")
        p.finish(0, null)
        assertEquals(listOf("percent", "done"), modes().takeLast(2))
        assertEquals("99.5", sent[sent.size - 2]["percent"]!!.jsonPrimitive.content)
    }

    private fun call(args: String) = object : ToolCall {
        override val arguments: JsonObject = Json.parseToJsonElement(args).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String = "S1"
        override val clientRole: String = "backend"
        override val clientProjectId: String = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
    }

    @Test fun `a background exec with progress reports its output to the session, sets done, lists the job and hands state to the tick`() {
        val root: File = Files.createTempDirectory("job-root").toFile().canonicalFile
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        val reports = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val exec = ExecTools({ if (it == "scripting.exec.enabled") "true" else null }, { root }, { n, p -> events += n to p }, { },
            report = { s, _, a -> reports += s to a })
        val q = { s: String -> Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s)) }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val cp = System.getProperty("java.class.path")
        val argv = listOf(java, "-cp", cp, ExecProbe::class.java.name, "say", "##infantry plan x|y", "hello", "##infantry step x").joinToString(",", "[", "]") { q(it) }
        val id = Json.parseToJsonElement(exec.exec.call(call("""{"command":$argv,"background":true,"script":"w","progress":{"label":"mybuild"}}""")).text).jsonObject["id"]!!.jsonPrimitive.content
        val deadline = System.currentTimeMillis() + 20_000
        while (events.none { it.first == "exec.done" } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(reports.all { it.first == "S1" })
        val ms = reports.map { it.second["mode"]!!.jsonPrimitive.content }
        assertEquals("done", ms.last()); assertTrue("plan" in ms, ms.toString())
        val list = Json.parseToJsonElement(exec.status.call(call("{}")).text).jsonArray
        assertEquals(id, list[0].jsonObject["id"]!!.jsonPrimitive.content); assertEquals("mybuild", list[0].jsonObject["label"]!!.jsonPrimitive.content)
        val argv2 = listOf(java, "-cp", cp, ExecProbe::class.java.name, "sleep", "1500").joinToString(",", "[", "]") { q(it) }
        val id2 = Json.parseToJsonElement(exec.exec.call(call("""{"command":$argv2,"background":true}""")).text).jsonObject["id"]!!.jsonPrimitive.content
        exec.status.call(call("""{"id":"$id2","state":{"n":1}}"""))
        exec.tick()
        val t = Json.parseToJsonElement(events.last { it.first == "exec.tick" }.second).jsonObject
        assertEquals(JsonArray(emptyList()), t["newLines"]); assertEquals("1", t["state"]!!.jsonObject["n"]!!.jsonPrimitive.content)
    }
}
