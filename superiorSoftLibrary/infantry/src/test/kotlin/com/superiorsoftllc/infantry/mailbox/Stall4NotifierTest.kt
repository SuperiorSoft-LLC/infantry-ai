package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.runner.WatchNotices
import com.superiorsoftllc.infantry.session.BgJob
import com.superiorsoftllc.infantry.session.BgNotify
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * STALL-4 (ticket 14ac9383): what the daemon's watcher SENDS. Every notice is role-neutral; an escalation goes to the
 * recipient rule's address (here [WatchNotices.USER]); a followed file's growth is output; a redirect not followed gets one
 * hint; reads are bounded (a deleted file, a huge file, a huge line); the done notice names a failure marker.
 */
class Stall4NotifierTest {
    private var now = 10_000_000L
    private val sent = ArrayList<MailSendRequest>()
    private val live = ArrayList<Pair<String, BgJob>>()

    private fun notifier(recipient: String? = WatchNotices.USER) = BgJobNotifier(
        jobs = { live.toList() },
        send = { sent += it; MailResult.Refused("test") },
        defaultTailLines = { 6 },
        defaultTimeoutMs = { 15 * 60_000L },
        patternGraceMs = { 0L },
        clock = { now },
        quietMs = { 5 * 60_000L },
        heartbeatCapMs = { 30 * 60_000L },
        pidAlive = { true },
        recipientOf = { recipient },
        lineChars = { 200 },
    )

    private fun tmp(text: String = "") = File.createTempFile("stall4", ".log").apply { deleteOnExit(); writeText(text) }
    private fun min(m: Long) { now += m * 60_000L }
    private fun assertNeutral() = sent.forEach { m ->
        val words = WatchNotices.roleWords(m.subject + "\n" + m.body)
        assertTrue(words.isEmpty(), "role words $words in: ${m.subject}\n${m.body}")
    }

    @Test
    fun theQuietNoticeIsNeutral_andAnUnfollowedRedirectGetsOneHint() {
        val n = notifier()
        val log = tmp("[infantry:start pid=1]\n")
        live += "s1" to BgJob("j1", "gradle test", 1, now, logPath = log.absolutePath, notify = BgNotify(), redirects = listOf("C:\\tmp\\x.log"))
        n.scanned()
        min(6)
        n.scanned()
        assertEquals(1, sent.size)
        val body = sent[0].body
        assertTrue(body.startsWith("task hanging: j1 no output "), body)
        assertEquals(1, body.trim().lines().size, body)
        assertNeutral()
    }

    @Test
    fun aFollowedFileGrowingIsOutput_soNoFalseQuietNotice() {
        val n = notifier()
        val log = tmp("[infantry:start pid=1]\n")
        val out = tmp("old content that is not new output\n")
        live += "s1" to BgJob("j1", "gradle test", 1, now, logPath = log.absolutePath, notify = BgNotify(), follow = listOf(out.absolutePath))
        n.scanned()
        repeat(4) { min(2); out.appendText("> Task :test $it\n"); n.scanned() }
        assertEquals(0, sent.size, "the file grew every 2 min: never quiet")
        min(6)
        n.scanned()
        assertEquals(1, sent.size)
        assertTrue(sent[0].body.startsWith("task hanging: j1 no output 6m"), sent[0].body)
        assertTrue(sent[0].body.trimEnd().endsWith("check log ${out.absolutePath}"), "the line names the followed file: ${sent[0].body}")
    }

    @Test
    fun aDeletedOrRotatedFollowedFileIsReadWithoutCrashing() {
        val n = notifier()
        val out = tmp("aaaaaaaa\n")
        live += "s1" to BgJob("j1", "watch x", 1, now, notify = BgNotify(), follow = listOf(out.absolutePath))
        n.scanned()
        out.delete()
        min(1); n.scanned()
        out.writeText("b\n") // rotated: a new, shorter file at the same path
        min(1); n.scanned()
        min(1); n.scanned()
        assertTrue(sent.none { it.body.contains("could not read") }, sent.joinToString { it.body })
        assertEquals(60L, n.quietSeconds("j1"), "the new file's line counted as output")
    }

    @Test
    fun aHugeFileAndAHugeLineAreReadBounded_andNoticesCutLines() {
        val n = notifier()
        val out = tmp("")
        live += "s1" to BgJob("j1", "watch big", 1, now, notify = BgNotify(patterns = listOf("NEEDLE")), follow = listOf(out.absolutePath))
        n.scanned()
        out.appendText("x".repeat(3 * 1024 * 1024) + "\n" + "NEEDLE " + "y".repeat(5_000) + "\n")
        min(1); n.scanned()
        assertTrue(sent.isEmpty(), "BG-NATIVE-1: a pattern match is never mailed")
        assertTrue(BgJobNotifier.readTail(out, 6, 64 * 1024L).size <= 6)
    }

    @Test
    fun doneNamesAFailureMarkerAndAPipeWithoutPipefail() {
        val n = notifier()
        val job = BgJob("j1", "gradle test | tail", 1, now, logPath = tmp("x\n").absolutePath, notify = BgNotify())
        n.done("s1", job, 0, listOf("BUILD FAILED in 3s"), bytes = 10, eof = true, marker = "BUILD FAILED in 3s", pipeNoFail = true)
        val m = sent.single()
        assertTrue(m.subject.contains("exit 0, failure marker"), m.subject)
        assertTrue(m.body.startsWith("task finished: j1 exit 0 (failure marker: BUILD FAILED in 3s)"), m.body)
        assertEquals(1, m.body.trim().lines().size, "one line")
        assertNeutral()
    }

    @Test
    fun aPausedLogIsToldOnce_andSilencesTheQuietNotice() {
        val n = notifier()
        val log = tmp("[infantry:start pid=1]\n")
        live += "s1" to BgJob("j1", "gradle", 1, now, logPath = log.absolutePath, notify = BgNotify(), logPaused = "free disk space 10 MB is below the floor of 200 MB")
        n.scanned()
        min(6); n.scanned()
        assertEquals(1, sent.size)
        assertTrue(sent[0].subject.contains("log file paused"), sent[0].subject)
        assertNeutral()
    }

    @Test
    fun theLongWaitEscalationGoesToTheRecipientRule_neverToARoleAndNotWhenItSaysNobody() {
        for ((recipient, expected) in listOf(WatchNotices.USER to 1, null to 0)) {
            sent.clear(); live.clear(); now = 10_000_000L
            val n = notifier(recipient)
            live += "s1" to BgJob("j1", "gradle", 1, now, notify = BgNotify())
            min(15); n.scanned() // beat at 15, next interval 30 = the cap: escalated
            assertEquals(expected, sent.count { it.to == WatchNotices.USER }, "recipient $recipient")
            assertTrue(sent.none { it.to == "pm" })
            assertNeutral()
        }
    }
}
