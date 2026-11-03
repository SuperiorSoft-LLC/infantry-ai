// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.session.BgJob
import com.superiorsoftllc.infantry.session.BgNotify
import kotlin.test.Test
import kotlin.test.assertEquals

/** BG-FOLD-1: bg.notice.foldIntoTurn / skipIfResultRead, each value. */
class BgFoldTest {
    private val sent = ArrayList<MailSendRequest>()
    private var busy = false

    private fun n(fold: Boolean, skip: Boolean) = BgJobNotifier(
        jobs = { emptyList() }, send = { sent += it; MailResult.Refused("t") },
        defaultTailLines = { 3 }, defaultTimeoutMs = { Long.MAX_VALUE }, patternGraceMs = { 0L },
        inTurn = { busy }, foldIntoTurn = { fold }, skipIfResultRead = { skip },
    )
    private val job = BgJob("j1", "build", pid = 1, startedAt = 0, notify = BgNotify(patterns = emptyList()))

    @Test fun foldOn_midTurnEnd_isParkedThenSentWhenIdle() {
        val x = n(true, true); busy = true
        x.done("s", job, 0, listOf("ok")); assertEquals(0, sent.size)
        x.scanned(); assertEquals(0, sent.size)
        busy = false; x.scanned(); assertEquals(1, sent.size)
    }

    @Test fun foldOn_resultReadMeanwhile_noWake() {
        val x = n(true, true); busy = true
        x.done("s", job, 0, listOf("ok")); x.ack("s", "j1")
        busy = false; x.scanned(); assertEquals(0, sent.size)
    }

    @Test fun foldOn_skipOff_readResultStillSent() {
        val x = n(true, false); busy = true
        x.done("s", job, 0, listOf("ok")); x.ack("s", "j1")
        busy = false; x.scanned(); assertEquals(0, sent.size) // ack drops the parked one regardless
        x.done("s", job.copy(id = "j2"), 0, listOf("ok")); assertEquals(1, sent.size)
    }

    @Test fun foldOff_midTurnEnd_sentAtOnce() {
        val x = n(false, true); busy = true
        x.done("s", job, 0, listOf("ok")); assertEquals(1, sent.size)
    }
}
