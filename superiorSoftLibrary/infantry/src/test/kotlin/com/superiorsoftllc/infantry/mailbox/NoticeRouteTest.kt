// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.session.BgJob
import com.superiorsoftllc.infantry.session.BgNotify
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** NOTICE-ROUTE-1: bg quiet/heartbeat notices are opt-in; the finished line always goes; the new settings default to no agent mail. */
class NoticeRouteTest {
    private var now = 10_000_000L
    private val sent = ArrayList<MailSendRequest>()
    private val live = ArrayList<Pair<String, BgJob>>()

    private fun notifier(status: Boolean) = BgJobNotifier(
        jobs = { live.toList() }, send = { sent += it; MailResult.Refused("test") },
        defaultTailLines = { 3 }, defaultTimeoutMs = { 15 * 60_000L }, patternGraceMs = { 0L }, clock = { now },
        quietMs = { 5 * 60_000L }, pidAlive = { true }, statusNotices = { status },
    )

    private fun run(status: Boolean): Int {
        sent.clear(); live.clear()
        val log = File.createTempFile("nroute", ".log").apply { deleteOnExit(); writeText("[infantry:start pid=1]\n") }
        val n = notifier(status)
        val j = BgJob("j1", "gradle test", 1, now, logPath = log.absolutePath, notify = BgNotify())
        live += "s1" to j
        n.scanned(); now += 6 * 60_000L; n.scanned(); now += 20 * 60_000L; n.scanned()
        val statusMails = sent.size
        live.clear(); n.done("s1", j, 0, emptyList())
        assertTrue(sent.any { it.subject.contains("exit 0") }, "the finished line is always sent")
        return statusMails
    }

    @Test fun statusNoticesOffSendsOnlyTheFinishedLine() = assertEquals(0, run(false))
    @Test fun statusNoticesOnStillSendsQuiet() = assertTrue(run(true) > 0)

    @Test fun healthAndStatusMailSettingsDefaultToOff() {
        assertEquals(0L, LimitRegistry.NOTICE_MAIL_PLUGIN_HEALTH.default)
        assertEquals(0L, LimitRegistry.NOTICE_MAIL_BG_STATUS.default)
    }
}
