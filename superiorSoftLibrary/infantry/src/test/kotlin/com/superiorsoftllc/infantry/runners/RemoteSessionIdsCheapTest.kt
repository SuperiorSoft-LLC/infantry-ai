// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BOARD-HANG-2: every session read (`infantry_board` once per worker) lists the remote mirror. The mirror's ids must come from the
 * hub's session index only: the owner's thread dump showed the board call stuck in the LOCAL runner's hello (harness PATH lookups,
 * model lists, a settings read on a fresh SQLite connection) that the old `runners()` path built just to filter it out.
 */
class RemoteSessionIdsCheapTest {
    private fun countingLocal(calls: AtomicInteger): RunnerLink =
        Proxy.newProxyInstance(RunnerLink::class.java.classLoader, arrayOf(RunnerLink::class.java)) { _, m, _ ->
            if (m.name == "toString") "local" else { calls.incrementAndGet(); error("the local runner must not be asked: ${m.name}") }
        } as RunnerLink

    @Test
    fun `remote session ids never touch the local runner`() {
        val localCalls = AtomicInteger()
        val remoteLookups = AtomicInteger()
        val hub = RunnerHub(countingLocal(localCalls), remotes = { remoteLookups.incrementAndGet(); listOf("r2") })
        val mirror = RemoteSessionMirror(sessionIds = { hub.remoteSessionIds() })

        assertEquals(emptyList(), mirror.list().map { it.id })
        assertEquals(0, remoteLookups.get(), "no placed session = no lookup of the paired runners")

        hub.placed("s-2", "r2")
        hub.placed("s-gone", "unpaired")
        repeat(50) { assertEquals(listOf("s-2"), mirror.list().map { it.id }) }
        assertEquals(0, localCalls.get(), "the local runner's hello/health is never built for a session read")
    }
}
