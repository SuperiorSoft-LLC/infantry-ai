// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNTIME-LOAD-6: the pod guard's lineage is the persisted `spawnerSessionId` chain (it survives a restart, which the in-memory
 * map did not), a released session gives its slot back, and [SpawnGuard.admitIf] charges only what would run a worker.
 */
class SpawnGuardLineageTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private fun s(id: String, spawner: String? = null, state: SessionState = SessionState.IDLE) =
        SessionRecord(id = id, provider = "fake", workingDir = ".", spawnedAt = 1L, lastHeartbeat = 1L, state = state, providerSessionId = "p", spawnerSessionId = spawner)

    private val records = mutableListOf(s("root"), s("child", spawner = "root"), s("grand", spawner = "child"), s("orphan", spawner = "gone"))
    private val released = mutableSetOf<String>()
    private fun guard(cap: Int = 4, depth: Int = 1) =
        SpawnGuard({ id -> records.firstOrNull { it.id == id } }, maxPerPod = cap, maxDepth = depth, all = { records.toList() }, released = { it.id in released })

    @Test fun `depth and pod come from the persisted chain, a missing spawner still counting as a level`() {
        val g = guard()
        assertNull(g.placementOf("root"))
        assertEquals(SpawnGuard.Placement("root", 1), g.placementOf("child"))
        assertEquals(SpawnGuard.Placement("root", 2), g.placementOf("grand"))
        assertEquals(SpawnGuard.Placement("gone", 1), g.placementOf("orphan"))
        val e = assertFailsWith<SpawnRefused> { g.check("child") }
        assertTrue(e.message!!.startsWith(SpawnGuard.REFUSED_DEPTH), e.message)
        assertEquals(2, g.liveIn("root"), "child and grand, from the records alone")
    }

    @Test fun `a narrower depth applies, a wider one does not`() {
        assertFailsWith<SpawnRefused> { guard(depth = 1).check("root", narrowDepth = 0) }
        assertFailsWith<SpawnRefused> { guard(depth = 1).check("child", narrowDepth = 5) }
    }

    @Test fun `ended and released sessions give their slot back`() {
        val g = guard(cap = 2)
        assertFailsWith<SpawnRefused> { g.check("root") }.let { assertTrue(it.message!!.startsWith(SpawnGuard.REFUSED_CAP)) }
        released += "grand"
        assertEquals(1, g.liveIn("root"))
        records.replaceAll { if (it.id == "grand") it.copy(state = SessionState.BUSY) else it }
        assertEquals(2, g.liveIn("root"), "a released session that runs again holds its slot")
        records.replaceAll { if (it.id == "grand") it.copy(state = SessionState.EXITED) else it }
        assertEquals(SpawnGuard.Placement("root", 1), g.check("root"))
    }

    @Test fun `admitIf checks only a charged start`() {
        val g = guard(cap = 0)
        assertEquals("ran", g.admitIf("root", charged = false) { p -> assertNull(p); "ran" })
        assertFailsWith<SpawnRefused> { g.admitIf("root", charged = true) { "never" } }
        assertNull(g.admitIf(null, charged = true) { p -> p }, "a move by nobody is not charged")
    }
}
