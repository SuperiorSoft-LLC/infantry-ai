package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.BgNotify
import com.superiorsoftllc.agenthq.session.ProcInfo
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STALL-1 (ticket 802c64d5): the daemon's watcher, heartbeat and stall detector. None of them has a model to call: they
 * get only a `send` (mail) and read files / pids, so "no model call" is structural; the counters say what they did.
 */
class StallWatchTest {
    private var now = 10_000_000L
    private val sent = ArrayList<MailSendRequest>()
    private val live = ArrayList<Pair<String, BgJob>>()
    private var busy = false
    private var alive = true

    private fun notifier(quietMin: Long = 5) = BgJobNotifier(
        jobs = { live.toList() },
        send = { sent += it; MailResult.Refused("test") },
        defaultTailLines = { 20 },
        defaultTimeoutMs = { 15 * 60_000L },
        patternGraceMs = { 20_000L },
        clock = { now },
        quietMs = { quietMin * 60_000L },
        heartbeatCapMs = { 60 * 60_000L },
        inTurn = { busy },
        pidAlive = { alive },
        recipientOf = { "pm" },
    )

    private fun log(text: String = "") = File.createTempFile("stall", ".log").apply { deleteOnExit(); writeText(text) }

    private fun job(log: File?, attached: Boolean = false, optOut: String? = null) =
        BgJob("j1", "gradle test", pid = 1, startedAt = now, logPath = log?.absolutePath, notify = BgNotify(), attached = attached, optOut = optOut)

    private fun min(m: Long) { now += m * 60_000L }

    @Test
    fun quietForNMinutes_informsOnce_andAgainOnlyAfterNewOutput() {
        val f = log("a\n")
        val n = notifier()
        live += "s" to job(f)
        n.scanned()
        min(3); n.scanned()
        assertTrue(sent.isEmpty())
        min(3); n.scanned(); n.scanned()
        assertEquals(1, sent.size, sent.map { it.subject }.toString())
        assertTrue(sent[0].subject.contains("quiet for 6m"), sent[0].subject)
        // The runner's own still-running lines are not output.
        f.appendText("[agenthq:running elapsed=7m]\n")
        min(1); n.scanned()
        assertEquals(1, sent.size)
        f.appendText("b\n")
        n.scanned()
        min(6); n.scanned()
        assertEquals(2, sent.size)
    }

    @Test
    fun heartbeat_withAFrozenLogAndALiveProcess_growsToTheCap_thenTellsThePmOnce() {
        val n = notifier(quietMin = 0)
        live += "s" to job(log("only line\n"))
        n.scanned()
        min(15); n.scanned()
        assertEquals(1, sent.size)
        assertTrue(sent[0].subject.contains("still running after 15m"), sent[0].subject)
        assertTrue(sent[0].body.contains("its process is still running"), sent[0].body)
        assertTrue(sent[0].body.contains("no output since"), sent[0].body)
        assertTrue(sent[0].body.contains("If you are waiting on it, end your turn"), sent[0].body)
        min(29); n.scanned()
        assertEquals(1, sent.size, "the second beat is 30 min after the first")
        min(1); n.scanned()
        assertEquals(3, sent.size, "30 min interval -> next is 60 = the cap: the beat plus one PM line")
        assertEquals("pm", sent[2].to)
        min(60); n.scanned()
        assertEquals(4, sent.size, "capped at 60 min, the PM is not told twice")
        assertEquals("s", sent[3].to)
    }

    @Test
    fun heartbeat_aVanishedLogAndAGoneProcess_areSaid() {
        val f = log("x\n")
        val n = notifier(quietMin = 0)
        live += "s" to job(f)
        n.scanned()
        f.delete()
        alive = false
        min(15); n.scanned()
        val body = sent.single().body
        assertTrue(body.contains("the log is gone"), body)
        assertTrue(body.contains("its process is GONE"), body)
    }

    @Test
    fun truncationAndRotation_areReadFromTheStart() {
        val f = log("one\ntwo\n")
        val n = notifier(quietMin = 1)
        val j = job(f).copy(notify = BgNotify(patterns = listOf("BUILD")))
        live += "s" to j
        n.scanned()
        f.writeText("BUILD\n") // shorter than before: a new file
        n.scanned()
        now += 21_000; n.scanned()
        assertTrue(sent.any { it.subject.contains("matched 'BUILD'") }, sent.map { it.subject }.toString())
    }

    @Test
    fun anAttachedJobInAnInFlightTurn_isNotMailed_butItsEndIsRecorded() {
        val n = notifier()
        val j = job(log("a\n"), attached = true)
        busy = true
        n.done("s", j, 0, listOf("a"), bytes = 2, eof = true)
        assertTrue(sent.isEmpty(), "the turn saw the end on its own console")
        assertEquals(now, n.lastEndedAt("s"))
        busy = false
        n.done("s", j, 0, listOf("a"), bytes = 2, eof = true)
        assertEquals(1, sent.size, "at rest: the end wakes it")
    }

    @Test
    fun doneFacts_noOutput_and_aChildStillHoldingThePipe() {
        val n = notifier()
        n.done("s", job(log()), 0, emptyList(), bytes = 0, eof = true)
        assertTrue(sent[0].subject.contains("exit 0, no output"), sent[0].subject)
        assertTrue(sent[0].body.contains("NO output (EOF, 0 bytes)"), sent[0].body)
        n.done("s", job(log()), 0, listOf("x"), bytes = 2, eof = false, survivors = listOf("4242 java -jar server.jar"))
        assertTrue(sent[1].body.contains("output pipe is still open") && sent[1].body.contains("4242"), sent[1].body)
    }

    @Test
    fun eofWhileRunning_isNotDone() {
        val n = notifier()
        n.eof("s", job(log("a\n")), 2)
        assertTrue(sent.single().subject.contains("closed its output and is still running"))
        assertNull(n.lastEndedAt("s"), "not an end")
    }

    @Test
    fun expiredReadsTheExitFile_elseSaysRunnerLost() {
        val n = notifier()
        val f = log("a\n")
        File(f.absolutePath.removeSuffix(".log") + ".exit").apply { deleteOnExit(); writeText("7") }
        n.expired("s", job(f))
        assertTrue(sent[0].subject.contains("finished, exit 7"), sent[0].subject)
        n.expired("s", job(log("b\n")))
        assertTrue(sent[1].body.contains("runner lost, exit unknown"), sent[1].body)
    }

    @Test
    fun aProcessUnderTheHarnessEnding_wakesASessionAtRest_withExitUnknown() {
        val n = notifier()
        val p = ProcInfo(77, 1, "bash -c gradlew test", now - 120_000)
        busy = true
        n.treeJobEnded("s", p, now)
        assertTrue(sent.isEmpty())
        assertEquals(now, n.lastEndedAt("s"))
        busy = false
        n.treeJobEnded("s", p, now)
        assertTrue(sent.single().body.contains("exit code is unknown"), sent.single().body)
    }

    @Test
    fun anOptOutIsLoggedCountedAndReportedToThePmOnce_andStillGetsTheHeartbeat() {
        val n = notifier()
        val before = WatchMeter.counts().optOuts
        live += "s" to job(null, attached = true, optOut = "needs a tty")
        n.scanned(); n.scanned()
        assertEquals(before + 1, WatchMeter.counts().optOuts)
        assertEquals(1, sent.count { it.to == "pm" && it.subject.contains("watch OFF") })
        min(15); n.scanned()
        assertTrue(sent.any { it.to == "s" && it.subject.contains("still running") }, sent.map { it.subject }.toString())
        assertTrue(sent.last().body.contains("watch OFF, needs a tty"), sent.last().body)
    }

    // ---- stall detector -------------------------------------------------------------------------------------------

    private class StallRig(autoExit: Boolean, foregroundMin: Long? = null) {
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = autoExit), tempSessionStore(), killGraceMs = 50)
        val sent = mutableListOf<MailSendRequest>()
        var offsetMin = 0L
        var ended: Long? = null
        var waiting = false
        var inProgress = true
        val detector = StallDetector(
            manager, send = { sent += it; MailResult.Refused("t") },
            ticketInProgress = { inProgress },
            deliberateWait = { waiting },
            lastJobEndedAt = { ended },
            quietSeconds = { null },
            stallMs = { 5 * 60_000L },
            recipientOf = { "pm" },
            clock = { System.currentTimeMillis() + offsetMin * 60_000 },
            foregroundStallMs = { foregroundMin?.let { it * 60_000L } ?: Long.MAX_VALUE },
        )
        fun at(m: Long) { offsetMin = m; detector.tick() }
        fun record(id: String): SessionRecord = manager.get(id)!!
        fun settle(id: String) {
            val until = System.currentTimeMillis() + 5_000
            while (record(id).state != com.superiorsoftllc.agenthq.session.SessionState.IDLE && System.currentTimeMillis() < until) Thread.sleep(20)
        }
    }

    @Test
    fun idleTicketWorker_isNudgedOnce_andThePmToldOnce_neverKilled() {
        val r = StallRig(autoExit = true)
        val s = r.manager.startNew("work", ticketId = "t1")
        r.settle(s.id)
        r.at(1)
        assertTrue(r.sent.isEmpty())
        assertEquals("idle", r.record(s.id).watchLabel)
        r.at(6); r.at(7)
        assertEquals(1, r.sent.count { it.to == s.id })
        assertEquals(1, r.sent.count { it.to == "pm" })
        assertEquals("stalled", r.record(s.id).watchLabel)
        assertTrue(r.manager.get(s.id) != null)
    }

    @Test
    fun aDeliberateWait_orATicketNotInProgress_orNoTicket_isNotNudged() {
        val r = StallRig(autoExit = true)
        val s = r.manager.startNew("work", ticketId = "t1")
        val plain = r.manager.startNew("chat")
        r.settle(s.id); r.settle(plain.id)
        r.waiting = true
        r.at(10)
        r.waiting = false
        r.inProgress = false
        r.at(11)
        assertTrue(r.sent.isEmpty(), r.sent.map { it.subject }.toString())
        assertNull(r.record(plain.id).watchLabel, "no change for a session with no ticket and no job")
        assertNotNull(r.record(s.id).watchLabel)
    }

    @Test
    fun busyButSilent_afterItsJobEnded_isDetected() {
        val r = StallRig(autoExit = false)
        val s = r.manager.startNew("long job", ticketId = "t1")
        r.at(6)
        assertTrue(r.sent.isEmpty(), "busy and silent with no ended job: not this detector's case")
        assertEquals("busy, silent 6m", r.record(s.id).watchLabel)
        r.ended = System.currentTimeMillis() + 6 * 60_000L
        r.at(10)
        assertTrue(r.sent.isEmpty(), "ended 4 min ago")
        r.at(12); r.at(13)
        assertEquals(1, r.sent.count { it.to == s.id && it.subject.contains("a job ended") })
        assertEquals(1, r.sent.count { it.to == "pm" })
    }

    @Test
    fun busyInALongForegroundCall_withNoJobEnded_isNudgedOnce_byTheDaemonTimerAlone() {
        // STALL-3 item 4: a hung or very long foreground call (no tool progress, no watched job, no job ended).
        val r = StallRig(autoExit = false, foregroundMin = 10)
        val s = r.manager.startNew("find / -name x", ticketId = "t1")
        r.at(9)
        assertTrue(r.sent.isEmpty(), "under the foreground limit")
        r.at(11); r.at(12); r.at(20)
        assertEquals(1, r.sent.count { it.to == s.id && it.subject.contains("no tool progress") }, r.sent.map { it.subject }.toString())
        assertEquals(1, r.sent.count { it.to == "pm" })
        assertEquals("stalled", r.record(s.id).watchLabel)
        assertTrue(r.manager.get(s.id) != null, "never killed")
    }

    @Test
    fun foregroundLimitZeroOrNever_isNotNudged() {
        val r = StallRig(autoExit = false, foregroundMin = 0)
        r.manager.startNew("long", ticketId = "t1")
        r.at(60)
        assertTrue(r.sent.isEmpty(), r.sent.map { it.subject }.toString())
    }
}
