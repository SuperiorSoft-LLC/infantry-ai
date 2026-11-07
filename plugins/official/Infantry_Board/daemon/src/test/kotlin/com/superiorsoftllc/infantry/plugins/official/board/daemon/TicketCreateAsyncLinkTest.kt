// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TICKET-CREATE-HANG-1: the slow relatedTo linking never holds the create answer; its failure is reported, not lost. */
class TicketCreateAsyncLinkTest {
    @Test fun slowWorkDoesNotBlockTheCaller() {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val t = TicketSave.background("slow", { release.await(30, TimeUnit.SECONDS) }) {}
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "background() must return at once")
        assertTrue(t.isAlive)
        release.countDown()
        t.join(5_000)
    }

    @Test fun failureGoesToTheHandler() {
        val seen = ArrayList<String>()
        TicketSave.background("boom", { error("locked") }) { seen += it.message.orEmpty() }.join(5_000)
        assertEquals(listOf("locked"), seen)
    }
}
