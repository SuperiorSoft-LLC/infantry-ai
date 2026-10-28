package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.cli.BgCli
import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.BgNotify
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BG-NOTIFY (ticket 245a7d31): the normal path, plus the one-mail rule for a pattern followed by an exit. */
class BgJobNotifierTest {
    private var now = 1_000_000L
    private val sent = ArrayList<MailSendRequest>()
    private val live = ArrayList<Pair<String, BgJob>>()

    private fun notifier() = BgJobNotifier(
        jobs = { live.toList() },
        send = { sent += it; MailResult.Refused("test") },
        defaultTailLines = { 3 },
        defaultTimeoutMs = { 30 * 60_000L },
        patternGraceMs = { 20_000L },
        clock = { now },
    )

    private fun job(log: File, notify: BgNotify? = BgNotify(patterns = BgCli.DEFAULT_PATTERNS)) =
        BgJob("j1", "gradle test", pid = 1, startedAt = now, logPath = log.absolutePath, notify = notify)

    @Test
    fun exitMailsTheSessionWithCodeAndTail_patternInsideGraceFoldsIntoIt() {
        val log = File.createTempFile("bgn", ".log").apply { deleteOnExit() }
        val n = notifier()
        val j = job(log)
        live += "sess-1" to j
        log.writeText("a\nb\nc\nBUILD FAILED in 3s\n")
        now += 2_000
        n.scanned()
        assertTrue(sent.isEmpty(), "a match waits for the grace: the job may still exit")
        live.clear()
        n.done("sess-1", j, 1, listOf("a", "b", "c", "BUILD FAILED in 3s"))
        assertEquals(1, sent.size)
        val m = sent.single()
        assertEquals("sess-1", m.to)
        assertEquals("system", m.from)
        assertTrue(m.subject.contains("exit 1"), m.subject)
        assertTrue(m.body.contains("matched:\n  BUILD FAILED"), m.body)
        assertTrue(m.body.contains("last 3 lines:\n  b\n  c\n  BUILD FAILED in 3s"), m.body)
    }

    @Test
    fun patternOnAStillRunningJob_andTimeout_eachMailOnce() {
        val log = File.createTempFile("bgn", ".log").apply { deleteOnExit() }
        val n = notifier()
        val j = job(log, BgNotify(patterns = listOf("READY"), timeoutMinutes = 10))
        live += "s" to j
        log.writeText("boot\nREADY on 8080\n")
        n.scanned()
        now += 21_000
        n.scanned()
        assertEquals(1, sent.size)
        assertTrue(sent[0].subject.contains("still running"), sent[0].subject)
        n.scanned()
        assertEquals(1, sent.size, "an old line is not matched again")
        now = j.startedAt + 10 * 60_000L + 1
        n.scanned()
        n.scanned()
        assertEquals(2, sent.size)
        assertTrue(sent[1].subject.contains("still running after 10m"), sent[1].subject)
    }

    @Test
    fun aJobWithoutNotifyIsSilent() {
        val log = File.createTempFile("bgn", ".log").apply { deleteOnExit() }
        val n = notifier()
        val j = job(log, notify = null)
        n.done("s", j, 0, emptyList())
        n.expired("s", j)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun cliParsesNotifyOptions() {
        val p = BgCli.parse(listOf("run", "--notify-on", "X+", "--notify-timeout", "0", "--name", "g", "--", "gradlew", "test")) as BgCli.Parsed.Run
        assertTrue(p.notify)
        assertEquals(listOf("X+") + BgCli.DEFAULT_PATTERNS, BgCli.patternsFor(p) { null })
        assertEquals(p, BgCli.parse(listOf("run") + p.optionArgs() + listOf("--", "gradlew", "test")))
    }
}
