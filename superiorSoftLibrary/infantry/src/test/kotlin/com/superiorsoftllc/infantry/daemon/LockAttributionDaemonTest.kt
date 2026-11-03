// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.db.LockFailureKind
import com.superiorsoftllc.infantry.db.StoreLockedException
import com.superiorsoftllc.infantry.persistence.SettingRow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Two REAL daemons over one state directory: the second is told WHICH daemon holds the lock. */
class LockAttributionDaemonTest {

    @Test
    fun aDaemonBlockedByAnotherDaemonIsToldWhichOne() {
        val first = DaemonFixture.launch(holderLabel = "daemon-A", busyTimeoutMs = 1_500)
        val second = DaemonFixture.launch(
            stateDir = first.stateDir, holderLabel = "daemon-B", busyTimeoutMs = 1_500,
        )
        val release = CountDownLatch(1)
        val holding = CountDownLatch(1)
        val holder = thread {
            first.daemon.store.transaction {
                first.daemon.store.settings.put(SettingRow("a", "1", 1))
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val e = assertFailsWith<StoreLockedException> {
                second.daemon.store.settings.put(SettingRow("b", "2", 1))
            }
            assertEquals(LockFailureKind.TIMEOUT, e.kind)
            assertTrue("locked by daemon-A" in e.message!!, e.message)
            assertTrue("daemon-B" !in e.message!!.substringBefore("locked by"), "must not blame itself: ${e.message}")
        } finally {
            release.countDown(); holder.join(10_000)
            second.stop(); first.stop()
        }
    }
}
