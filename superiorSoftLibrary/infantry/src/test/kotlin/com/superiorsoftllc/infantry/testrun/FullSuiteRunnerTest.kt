package com.superiorsoftllc.infantry.testrun

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullSuiteRunnerTest {
    private val tmp = Files.createTempDirectory("fsr").toFile()

    private fun xml(vararg cases: String) =
        File(tmp, "r${System.nanoTime()}.xml").also { it.writeText("<testsuite name=\"S\">${cases.joinToString("")}</testsuite>") }

    private val pass = "<testcase classname=\"a.A\" name=\"ok\"/>"
    private val skip = "<testcase classname=\"a.A\" name=\"sk\"><skipped/></testcase>"
    private val fail = "<testcase classname=\"a.A\" name=\"bad\"><failure message=\"boom\">trace</failure></testcase>"

    private class Sink : FailureSink {
        val created = mutableListOf<String>()
        val comments = mutableListOf<Pair<String, String>>()
        val mails = mutableListOf<String>()
        override fun create(title: String, description: String) = "t${created.size + 1}".also { created += title }
        override fun comment(ticketId: String, text: String) { comments += ticketId to text }
        override fun notifyNew(text: String) { mails += text }
    }

    private fun runner(sink: Sink, files: () -> List<File>, free: Int = 80, execCalls: MutableList<String> = mutableListOf()) =
        FullSuiteRunner(
            object : SuiteEnv {
                override fun headSha(dir: File) = "abcdef123456"
                override fun prepareWorktree(branch: String) = tmp
                override fun results(dir: File, glob: String?) = files()
                override fun suspectedArea(className: String) = listOf("ticket-1")
                override fun freeMemoryPercent() = free
                override fun now() = 1L
            },
            object : SuiteExec {
                override fun run(command: String, dir: File, timeoutMinutes: Long): SuiteExec.Out {
                    execCalls += command
                    return SuiteExec.Out(1, "out")
                }
            },
            sink,
        )

    private val cfg = TestRunConfig(fullCommand = "any-tool test", workingBranch = "staging", envOrArgs = "--workers 1")

    @Test
    fun parserReadsJunitXml() {
        val r = JunitXmlParser.parse(listOf(xml(pass, skip, fail)), 0, "")
        assertEquals(listOf(TestStatus.PASS, TestStatus.SKIP, TestStatus.FAIL), r.map { it.status })
        assertEquals("S|a.A|bad", r[2].identity)
        assertTrue(r[2].output.contains("boom"))
    }

    @Test
    fun refusesWhenUnset() {
        val sink = Sink()
        val calls = mutableListOf<String>()
        val l = runner(sink, { emptyList() }, execCalls = calls).run(TestRunConfig(null, "staging"), Ledger())
        assertEquals("refused", l.last?.outcome)
        assertTrue(l.last!!.message.contains("tests.fullCommand"))
        val l2 = runner(sink, { emptyList() }, execCalls = calls).run(TestRunConfig("x", null), Ledger())
        assertTrue(l2.last!!.message.contains("tests.workingBranch"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun newFailureCreatesOneTicketAndRepeatDedupes() {
        val sink = Sink()
        val calls = mutableListOf<String>()
        val f = xml(pass, fail)
        val l1 = runner(sink, { listOf(f) }, execCalls = calls).run(cfg, Ledger())
        assertEquals(1, sink.created.size)
        assertEquals(1, sink.mails.size)
        assertEquals("any-tool test --workers 1", calls[0])
        assertEquals("tests: 1 failing", FullSuiteRunner.status(l1))
        val l2 = runner(sink, { listOf(f) }).run(cfg, l1)
        assertEquals(1, sink.created.size)
        assertEquals(1, sink.mails.size)
        assertEquals(1, sink.comments.size)
        assertEquals(2, l2.failures.values.single().failCount)
    }

    @Test
    fun failThenPassOnRerunIsFlakyNotTicketed() {
        val sink = Sink()
        val ok = "<testcase classname=\"a.A\" name=\"bad\"/>"
        val second = xml(ok)
        val first = xml(fail)
        var n = 0
        val l = runner(sink, { if (n++ == 0) listOf(first) else listOf(second) })
            .run(cfg.copy(rerunCommand = "any-tool rerun"), Ledger())
        assertTrue(sink.created.isEmpty())
        assertTrue(l.failures.values.single().flaky)
        assertEquals("tests: green", FullSuiteRunner.status(l))
    }

    @Test
    fun skipsUnderMemoryPressureOrWhenRunning() {
        val calls = mutableListOf<String>()
        val l = runner(Sink(), { emptyList() }, free = 5, execCalls = calls).run(cfg.copy(memoryPressureThreshold = 20), Ledger())
        assertEquals("skipped", l.last?.outcome)
        val l2 = runner(Sink(), { emptyList() }, execCalls = calls).run(cfg, Ledger(running = true))
        assertEquals("skipped", l2.last?.outcome)
        assertTrue(calls.isEmpty())
        assertEquals("tests: running", FullSuiteRunner.status(Ledger(running = true)))
    }
}
