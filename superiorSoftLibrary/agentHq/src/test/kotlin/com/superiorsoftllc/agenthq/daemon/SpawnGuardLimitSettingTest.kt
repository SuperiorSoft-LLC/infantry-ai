package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pod guard reads its cap and depth live, names the setting in a refusal, and treats 0 as unlimited. */
class SpawnGuardLimitSettingTest {
    private val noSessions: (String) -> SessionRecord? = { null }

    @Test
    fun defaultsComeFromTheRegistry() {
        assertEquals(4, SpawnGuard.DEFAULT_MAX_PER_POD)
        assertEquals(2, SpawnGuard.DEFAULT_MAX_DEPTH)
        assertEquals(4, SpawnGuard(noSessions).maxPerPod)
    }

    @Test
    fun liveSourceWinsAndAChangeAppliesAtOnce() {
        var cap = 2
        val guard = SpawnGuard(noSessions, maxPerPodSource = { cap })
        assertEquals(2, guard.maxPerPod)
        cap = Int.MAX_VALUE
        assertEquals(Int.MAX_VALUE, guard.maxPerPod)
    }

    @Test
    fun aRefusalNamesTheLimitAndWhereToChangeIt() {
        val sessions = mapOf("root" to SessionRecord(id = "root", provider = "fake", workingDir = ".", spawnedAt = 1L, lastHeartbeat = 1L, state = SessionState.IDLE, providerSessionId = "p"))
        val guard = SpawnGuard(sessions::get, maxDepth = 0)
        val e = assertFailsWith<SpawnRefused> { guard.check("root") }
        assertTrue("limits.concurrency.maxDepth" in e.message!!, e.message)
        assertTrue("AGENTHQ_LIMIT_CONCURRENCY_MAX_DEPTH" in e.message!!, e.message)
    }
}
