// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.testrun

import com.superiorsoftllc.infantry.cli.TestsCli
import com.superiorsoftllc.infantry.daemon.plugins.LocalPluginStorage
import com.superiorsoftllc.infantry.db.InfantryDatabaseFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FULLSUITE-2: the service over a fake command: new failure = ticket + mail, repeat = comment, flaky = nothing, ledger survives a restart. */
class TestRunServiceTest {
    private val tmp = Files.createTempDirectory("trs").toFile()
    private val dbPath = File(tmp, "t.db").toString()

    private val failXml = "<testsuite name=\"S\"><testcase classname=\"a.A\" name=\"bad\"><failure message=\"boom\">trace</failure></testcase></testsuite>"
    private val passXml = "<testsuite name=\"S\"><testcase classname=\"a.A\" name=\"bad\"/></testsuite>"

    private class Recorder {
        val created = mutableListOf<String>()
        val comments = mutableListOf<Pair<String, String>>()
        val mails = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val sink = ServiceFailureSink({ t, _ -> "t${created.size + 1}".also { created += t } }, { id, x -> comments += id to x }, { mails += it })
    }

    /** [results] is what the result file holds for run N (0-based); a rerun reads the next entry. */
    private fun service(rec: Recorder, ledgers: LedgerStore, data: Map<String, String>, results: MutableList<String>, execs: MutableList<String> = mutableListOf()): TestRunService {
        val file = File(tmp, "result.xml")
        return TestRunService(
            ledgers, { data },
            { object : SuiteEnv {
                override fun headSha(dir: File) = "abcdef123456"
                override fun prepareWorktree(branch: String) = tmp
                override fun results(dir: File, glob: String?) = listOf(file)
                override fun suspectedArea(className: String) = emptyList<String>()
                override fun freeMemoryPercent() = 90
                override fun now() = 1L
            } },
            object : SuiteExec {
                override fun run(command: String, dir: File, timeoutMinutes: Long): SuiteExec.Out {
                    execs += command
                    file.writeText(results.removeAt(0))
                    return SuiteExec.Out(1, "out")
                }
            },
            { rec.sink }, async = { it.run() }, onStatus = { _, l -> rec.statuses += l },
        )
    }

    private val data = mapOf("tests.fullCommand" to "any-tool test", "tests.workingBranch" to "staging", "tests.resultGlob" to "*.xml")

    @Test
    fun `unset command or branch refuses with the clear message and runs nothing`() {
        val rec = Recorder(); val execs = mutableListOf<String>()
        val msg = service(rec, MemoryLedgerStore(), emptyMap(), mutableListOf(), execs).run("p1")
        assertTrue(msg.contains("tests.fullCommand is not set"), msg)
        assertTrue(execs.isEmpty())
        val msg2 = service(rec, MemoryLedgerStore(), mapOf("tests.fullCommand" to "x"), mutableListOf(), execs).run("p1")
        assertTrue(msg2.contains("tests.workingBranch is not set"), msg2)
    }

    @Test
    fun `new failure makes one ticket and one mail, a repeat only comments, ledger survives a restart`() {
        val rec = Recorder()
        var handle = InfantryDatabaseFactory.open(dbPath)
        service(rec, SqlLedgerStore(LocalPluginStorage(handle, "testrun")), data, mutableListOf(failXml)).run("p1")
        assertEquals(1, rec.created.size); assertEquals(1, rec.mails.size); assertTrue(rec.comments.isEmpty())
        handle.close()

        handle = InfantryDatabaseFactory.open(dbPath) // "restart": a new store over the same database
        val svc = service(rec, SqlLedgerStore(LocalPluginStorage(handle, "testrun")), data, mutableListOf(failXml))
        svc.run("p1")
        assertEquals(1, rec.created.size, "no second ticket after restart")
        assertEquals(1, rec.mails.size, "no second mail")
        assertEquals(listOf("t1"), rec.comments.map { it.first })
        assertTrue(svc.status("p1").contains("tests: 1 failing"), svc.status("p1"))
        assertEquals(listOf("tests: running", "tests: 1 failing"), rec.statuses.takeLast(2))
        handle.close()
    }

    @Test
    fun `fail then pass on the rerun is flaky and files nothing`() {
        val rec = Recorder(); val execs = mutableListOf<String>()
        val d = data + ("tests.rerunCommand" to "any-tool rerun")
        val ledgers = MemoryLedgerStore()
        service(rec, ledgers, d, mutableListOf(failXml, passXml), execs).run("p1")
        assertEquals(listOf("any-tool test", "any-tool rerun"), execs)
        assertTrue(rec.created.isEmpty() && rec.mails.isEmpty() && rec.comments.isEmpty())
        assertTrue(ledgers.load("p1").failures.values.single().flaky)
        assertNull(ledgers.load("p1").failures.values.single().ticketId)
    }

    @Test
    fun `schedule is off by default and starts a due run when set`() {
        val rec = Recorder(); val execs = mutableListOf<String>()
        service(rec, MemoryLedgerStore(), data, mutableListOf(passXml), execs).tick(listOf("p1"))
        assertTrue(execs.isEmpty(), "no tests.scheduleMinutes = no run")
        val ledgers = MemoryLedgerStore()
        service(rec, ledgers, data + ("tests.scheduleMinutes" to "30"), mutableListOf(passXml), execs).tick(listOf("p1"))
        assertEquals(1, execs.size)
        assertNotNull(ledgers.load("p1").last)
    }

    @Test
    fun `cli usage`() {
        assertNull(TestsCli.usageError(listOf("run")))
        assertNull(TestsCli.usageError(listOf("status", "p1")))
        assertEquals(TestsCli.USAGE, TestsCli.usageError(listOf("nope")))
        assertEquals(TestsCli.USAGE, TestsCli.usageError(emptyList()))
    }
}
