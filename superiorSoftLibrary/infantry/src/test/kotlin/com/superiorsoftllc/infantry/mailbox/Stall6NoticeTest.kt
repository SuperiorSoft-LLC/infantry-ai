// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.session.BgJob
import com.superiorsoftllc.infantry.session.BgNotify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** STALL-6: handled / ack / superseded / one-per-kind / honest wrapper exit for bg job notices. */
class Stall6NoticeTest {
    private var now = 1_000_000L
    private var dedupe = true
    private val sent = ArrayList<MailSendRequest>()
    private val dropped = ArrayList<Triple<String, String, String?>>()
    private val live = ArrayList<Pair<String, BgJob>>()

    private fun notifier() = BgJobNotifier(
        jobs = { live.toList() },
        send = { sent += it; MailResult.Refused("test") },
        defaultTailLines = { 3 },
        defaultTimeoutMs = { 30 * 60_000L },
        patternGraceMs = { 20_000L },
        clock = { now },
        drop = { thread, to, _, subject -> dropped += Triple(thread, to, subject); 1 },
        dedupe = { dedupe },
    )

    private fun job(id: String = "j1", label: String = "gradle test", startedAt: Long = now) =
        BgJob(id, label, pid = 1, startedAt = startedAt, notify = BgNotify())

    @Test
    fun ackDropsQueuedNoticesAndSuppressesLaterOnes() {
        val n = notifier()
        val j = job()
        assertEquals(1, n.ack("s", j.id))
        assertEquals(Triple("bg:j1", "s", null), dropped.single())
        n.done("s", j, 1, listOf("x"))
        assertTrue(sent.isEmpty(), "a handled job sends nothing more")
        assertTrue(n.isHandled("j1"))
    }

    @Test
    fun anUnreadJobStillGetsItsNotice_andTheNoticeSaysHowToAck() {
        val n = notifier()
        n.done("s", job(), 1, listOf("x"))
        assertEquals(1, sent.size)
        assertTrue(sent.single().body.startsWith("task finished: j1 exit 1"), sent.single().body)
    }

    @Test
    fun offSwitchSendsEverything() {
        dedupe = false
        val n = notifier()
        n.ack("s", "j1")
        n.eof("s", job(), 5)
        n.eof("s", job(), 5)
        n.done("s", job(), 1, listOf("x"))
        assertEquals(3, sent.size)
        assertTrue(sent.none { it.body.contains("bg ack") }, "no ack hint when the feature is off")
    }

    @Test
    fun atMostOneEofAndOneFinishedNoticePerJob_andEofAfterEndIsDropped() {
        val n = notifier()
        val j = job()
        n.eof("s", j, 5)
        n.eof("s", j, 5)
        assertEquals(1, sent.size)
        n.done("s", j, 0, emptyList(), bytes = 5)
        n.done("s", j, 0, emptyList(), bytes = 5)
        assertEquals(2, sent.size, "one 'closed output' then one 'finished'")
        assertTrue(dropped.any { it.third?.contains("closed its output") == true }, "the queued 'closed output' notice is dropped at the end")
        n.eof("s", job("j2").also { n.done("s", it, 0, emptyList()) }, 1)
        assertEquals(3, sent.size, "a 'closed output' notice after the job finished is not sent")
    }

    @Test
    fun aNewerJobWithTheSameLabelSupersedesTheOldOnesQueuedNotices() {
        val n = notifier()
        val old = job("old")
        n.done("s", old, 1, listOf("x"))
        dropped.clear()
        live += "s" to job("new", startedAt = now - 1)
        n.scanned()
        assertTrue(dropped.isEmpty(), "started before the old one ended: not a rerun")
        now += 5_000
        live.clear()
        live += "s" to job("new2", startedAt = now)
        n.scanned()
        assertEquals(Triple("bg:old", "s", null), dropped.single())
        assertTrue(n.isHandled("old"))
        live += "other" to job("x", label = "other", startedAt = now)
        n.scanned()
        assertEquals(1, dropped.size, "dropped once")
    }

    @Test
    fun wrapperEchoExitIsNotReportedAsTheCommandsExit() {
        val n = notifier()
        n.done("s", job(), 0, listOf("BUILD FAILED", "EXIT=1"))
        val body = sent.single().body
        assertTrue(body.startsWith("task finished: j1 exit 0"), body)
        sent.clear()
        n.done("s", job("j3", label = "./gradlew test > out.txt; echo EXIT=\$?"), 0, listOf("done"))
        assertEquals(1, sent.single().body.trim().lines().size, sent.single().body)
        sent.clear()
        n.done("s", job("j4"), 0, listOf("EXIT=0"))
        assertTrue(sent.single().body.startsWith("task finished: j4 exit 0"), sent.single().body)
    }

    @Test
    fun mailboxDropsOnlyQueuedMessagesOfTheThreadThatMatch() {
        val store = object : MailStore { override fun load() = emptyList<Message>(); override fun saveAll(messages: Collection<Message>) {} }
        val box = Mailbox(store)
        fun send(subject: String, thread: String) = box.send(MailSendRequest(from = "system", to = "s", subject = subject, body = "b", threadId = thread))
        send("a", "bg:j1"); send("b", "bg:j1"); send("c", "bg:j2")
        assertEquals(1, box.dropQueued("bg:j1", "s", "r") { it.subject == "a" })
        assertEquals(1, box.dropQueued("bg:j1", "s", "r"))
        assertEquals(0, box.dropQueued("bg:j1", "s", "r"))
        assertEquals(1, box.pendingCount("s"))
    }
}
