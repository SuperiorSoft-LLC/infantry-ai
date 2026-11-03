package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.session.ProgressReport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract 1.117.0: the `##infantry` marker grammar, the CLI forms, and that every command is harmless outside a session. */
class ProgressCliTest {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test fun `every marker form parses`() {
        val p = ProgressMarkers::parse
        assertEquals(ProgressReport("percent", percent = 40.0, label = "compile all"), p("##infantry progress 40 compile all"))
        assertEquals(ProgressReport("percent", percent = 62.5), p("   ##infantry   progress 62.5%"))
        assertEquals(ProgressReport("plan", steps = listOf("build", "unit tests", "ui")), p("##infantry plan build| unit tests |ui"))
        assertEquals(ProgressReport("plan", count = 4, label = "gate"), p("##infantry plan 4 gate"))
        assertEquals(ProgressReport("step"), p("##infantry step"))
        assertEquals(ProgressReport("step", step = "unit tests"), p("##infantry step unit tests"))
        assertEquals(ProgressReport("bytes", done = 10, total = 100, label = "jdk.zip"), p("##infantry bytes 10 100 jdk.zip"))
        assertEquals(ProgressReport("bytes", done = 10, total = null), p("##infantry bytes 10 ?"))
        assertEquals(ProgressReport("count", done = 3, total = 9), p("##infantry count 3 9"))
        assertEquals(ProgressReport("count", done = 3), p("##infantry count 3"))
        assertEquals(ProgressReport("status", label = "waiting"), p("##infantry status waiting"))
        assertEquals(ProgressReport("done", label = "ok"), p("##infantry done ok"))
        assertEquals(ProgressReport("done"), p("##infantry done"))
        assertEquals(ProgressReport("fail", label = "exit 1"), p("##infantry fail exit 1"))
        assertEquals(ProgressReport("clear"), p("##infantry clear"))
        assertEquals(ProgressReport("status", label = "gate", estimate = true), p("##infantry estimate gate"))
        assertEquals(ProgressReport("status", label = "gate", estimate = true), p("##infantry status --estimate gate"))
    }

    @Test fun `unparsable markers are ignored`() {
        for (line in listOf(
            "", "hello", "##infantry", "##infantryprogress 4", "##infantry nope 3", "##infantry progress", "##infantry progress abc",
            "##infantry progress NaN", "##infantry plan", "##infantry plan 0", "##infantry plan | |", "##infantry bytes x 3",
            "##infantry count 3 many", "##infantry status", "##infantry estimate", "echo ##infantry progress 4",
        )) assertNull(ProgressMarkers.parse(line), "'$line' must be ignored")
    }

    @Test fun `cli forms`() {
        assertEquals(ProgressReport("percent", percent = 40.0, label = "compile"), ProgressCli.parseCommand(listOf("40", "compile")))
        assertEquals(ProgressReport("plan", steps = listOf("a", "b"), label = "g"), ProgressCli.parseCommand(listOf("plan", "a", "b", "--label", "g")))
        assertEquals(ProgressReport("plan", count = 3), ProgressCli.parseCommand(listOf("plan", "3")))
        assertEquals(ProgressReport("step", step = "a"), ProgressCli.parseCommand(listOf("step", "a")))
        assertEquals(ProgressReport("count", done = 1, total = 2, label = "tests"), ProgressCli.parseCommand(listOf("count", "1", "2", "tests")))
        assertEquals(ProgressReport("get"), ProgressCli.parseCommand(listOf("show")))
        assertEquals(ProgressReport("status", label = "full gate", estimate = true), ProgressCli.parseCommand(listOf("estimate", "full", "gate")))
        assertNull(ProgressCli.parseCommand(listOf("estimate")), "an estimate needs its label")
        assertEquals(ProgressReport("status", label = "g", estimate = true), ProgressCli.parseCommand(listOf("status", "g", "--estimate")))
        assertNull(ProgressCli.parseCommand(listOf("status", "--estimate")))
        assertEquals(true, ProgressCli.parseStreamOptions(listOf("--estimate", "--label", "g", "--", "x"), allowCommand = true).estimate)
        assertEquals(false, ProgressCli.parseStreamOptions(listOf("--label", "g", "--", "x"), allowCommand = true).estimate, "never the default")
        assertTrue(ProgressCli.parseStreamOptions(listOf("--estimate"), allowCommand = false).bad!!.contains("--label"))
        assertNull(ProgressCli.parseCommand(listOf("plan")))
        assertNull(ProgressCli.parseCommand(listOf("clear", "x")))
        assertNull(ProgressCli.parseCommand(listOf("bogus")))
        assertNull(ProgressCli.parseCommand(emptyList()))
    }

    private fun neverOpen(): FrameStream = error("must not connect")
    private fun deadDaemon(): FrameStream = throw java.net.ConnectException("connection refused")
    private fun env(session: String?): (String) -> String? = { if (it == AccessPolicy.SESSION_ID_ENV) session else null }

    private fun watch(input: ByteArray, session: String?, open: () -> FrameStream): Triple<Int, ByteArray, String> {
        val out = ByteArrayOutputStream()
        val err = StringBuilder()
        val code = ProgressCli.progress(listOf("--watch", "--reader", "gate"), ByteArrayInputStream(input), out, err, env(session), open)
        return Triple(code, out.toByteArray(), err.toString())
    }

    @Test fun `watch copies bytes through unchanged, CRLF and no trailing newline included`() {
        val input = "a\r\n##infantry progress 40 x\r\nstage 2 of 4 unit\n\u00e9t\u00e9 \u2713\r\nlast line, no newline".toByteArray()
        for ((session, open) in listOf(null to ::neverOpen, "s-1" to ::deadDaemon)) {
            val (code, out, err) = watch(input, session, open)
            assertEquals(0, code)
            assertContentEquals(input, out, "byte for byte (session=$session)")
            assertEquals("", err, "silent (session=$session)")
        }
    }

    @Test fun `every report form exits 0 and prints nothing with no session or a dead daemon`() {
        val forms = listOf(listOf("40", "x"), listOf("plan", "a", "b"), listOf("step"), listOf("bytes", "1", "2"),
            listOf("count", "1", "?"), listOf("status", "x"), listOf("done"), listOf("fail", "why"), listOf("clear"), listOf("show"))
        for (form in forms) for ((session, open) in listOf(null to ::neverOpen, "s-1" to ::deadDaemon)) {
            val out = ByteArrayOutputStream()
            val err = StringBuilder()
            val code = ProgressCli.progress(form, ByteArrayInputStream(ByteArray(0)), out, err, env(session), open)
            assertEquals(0, code, "$form session=$session")
            assertEquals(0, out.size(), "$form session=$session")
            assertEquals("", err.toString(), "$form session=$session")
        }
        // A bad form outside a session is still exit 0; inside one it is a usage error.
        assertEquals(0, ProgressCli.progress(listOf("bogus"), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), StringBuilder(), env(null), ::neverOpen))
        assertEquals(2, ProgressCli.progress(listOf("bogus"), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), StringBuilder(), env("s-1"), ::deadDaemon))
    }

    private fun shell(script: String) = if (isWindows) listOf("cmd", "/c", script) else listOf("sh", "-c", script)

    @Test fun `run keeps the exit code, stdout and stderr`() {
        val script = if (isWindows) "echo out-line& echo ##infantry progress 50& echo err-line 1>&2& exit /b 3"
        else "echo out-line; echo '##infantry progress 50'; echo err-line 1>&2; exit 3"
        for ((session, open) in listOf(null to ::neverOpen, "s-1" to ::deadDaemon)) {
            val out = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val err = StringBuilder()
            val code = ProgressCli.run(listOf("--label", "t", "--") + shell(script), out, stderr, err, env(session), open)
            assertEquals(3, code)
            val text = out.toString(Charsets.UTF_8)
            assertTrue(text.contains("out-line") && text.contains("##infantry progress 50"), text)
            assertTrue(stderr.toString(Charsets.UTF_8).contains("err-line"), stderr.toString())
            assertEquals("", err.toString())
        }
        val ok = ProgressCli.run(listOf("--") + shell("exit 0"), ByteArrayOutputStream(), ByteArrayOutputStream(), StringBuilder(), env(null), ::neverOpen)
        assertEquals(0, ok)
        assertEquals(127, ProgressCli.run(listOf("--", "no-such-command-xyz-123"), ByteArrayOutputStream(), ByteArrayOutputStream(), StringBuilder(), env(null), ::neverOpen))
        assertEquals(2, ProgressCli.run(listOf("--label", "x"), ByteArrayOutputStream(), ByteArrayOutputStream(), StringBuilder(), env(null), ::neverOpen))
    }

    @Test fun `gate reader turns stage lines into a plan and steps`() {
        val r = ProgressReaders.gate()
        assertEquals(listOf(ProgressReport("plan", count = 4, label = "unit"), ProgressReport("step")), r.read("== stage 2 of 4: unit", null))
        assertTrue(r.read("nothing here", null).isEmpty())
    }

    @Test fun `shipped task reader reports the running task as a status, unchanged from the old code reader`() {
        val specs = com.superiorsoftllc.infantry.runner.ToolProfiles.DEFAULTS.flatMap { it.progress }.filter { it.reader == "gradle" }
        val r = ProgressReaders.data(specs)
        assertEquals(listOf(ProgressReport("status", label = "gradle :app:compileKotlin")), r.read("> Task :app:compileKotlin", null))
        assertTrue(r.read("> Configure project :", null).isEmpty())
        assertTrue(ProgressReaders.byName("nope") == null)
        assertTrue("junit" in ProgressReaders.names())
    }
}
