package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.runner.WatchNotices
import com.superiorsoftllc.agenthq.session.BgJob
import com.superiorsoftllc.agenthq.session.BgNotify
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
        val log = tmp("[agenthq:start pid=1]\n")
        live += "s1" to BgJob("j1", "gradle test", 1, now, logPath = log.absolutePath, notify = BgNotify(), redirects = listOf("C:\\tmp\\x.log"))
        n.scanned()
        min(6)
        n.scanned()
        assertEquals(1, sent.size)
        val body = sent[0].body
        assertTrue(body.contains("has printed nothing for"), body)
        assertTrue(body.contains("Output sent to a file is not visible to the watcher; to follow it use: agenthq watch-file C:\\tmp\\x.log"), body)
        assertNeutral()
    }

    @Test
    fun aFollowedFileGrowingIsOutput_soNoFalseQuietNotice() {
        val n = notifier()
        val log = tmp("[agenthq:start pid=1]\n")
        val out = tmp("old content that is not new output\n")
        live += "s1" to BgJob("j1", "gradle test", 1, now, logPath = log.absolutePath, notify = BgNotify(), follow = listOf(out.absolutePath))
        n.scanned()
        repeat(4) { min(2); out.appendText("> Task :test $it\n"); n.scanned() }
        assertEquals(0, sent.size, "the file grew every 2 min: never quiet")
        min(6)
        n.scanned()
        assertEquals(1, sent.size)
        assertTrue(sent[0].body.contains("following: ${out.absolutePath}"), sent[0].body)
        assertTrue(sent[0].body.contains("> Task :test 3"), "the tail comes from the followed file: ${sent[0].body}")
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
        val m = sent.single { it.subject.contains("matched") }
        assertTrue(m.body.length < 4_000, "bounded mail: ${m.body.length}")
        assertTrue(m.body.lines().all { it.length <= 210 }, "each line cut to ~200")
        assertTrue(BgJobNotifier.readTail(out, 6, 64 * 1024L).size <= 6)
    }

    @Test
    fun doneNamesAFailureMarkerAndAPipeWithoutPipefail() {
        val n = notifier()
        val job = BgJob("j1", "gradle test | tail", 1, now, logPath = tmp("x\n").absolutePath, notify = BgNotify())
        n.done("s1", job, 0, listOf("BUILD FAILED in 3s"), bytes = 10, eof = true, marker = "BUILD FAILED in 3s", pipeNoFail = true)
        val m = sent.single()
        assertTrue(m.subject.contains("exit 0, failure marker"), m.subject)
        assertTrue(m.body.contains("The exit code is 0 but the output contains a failure marker: BUILD FAILED in 3s"), m.body)
        assertTrue(m.body.contains("has a pipe and its shell has no pipefail"), m.body)
        assertNeutral()
    }

    @Test
    fun aPausedLogIsToldOnce_andSilencesTheQuietNotice() {
        val n = notifier()
        val log = tmp("[agenthq:start pid=1]\n")
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
