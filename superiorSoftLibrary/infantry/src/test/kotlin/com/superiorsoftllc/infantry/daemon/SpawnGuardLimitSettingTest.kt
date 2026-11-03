// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pod guard reads its cap and depth live, names the setting in a refusal, and treats 0 as unlimited. */
class SpawnGuardLimitSettingTest {
    /** No test here may hang a fork (a spinning test burned a core for 25 min once). */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val noSessions: (String) -> SessionRecord? = { null }

    @Test
    fun defaultsComeFromTheRegistry() {
        assertEquals(4, SpawnGuard.DEFAULT_MAX_PER_POD)
        assertEquals(1, SpawnGuard.DEFAULT_MAX_DEPTH)
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
        assertTrue("INFANTRY_LIMIT_CONCURRENCY_MAX_DEPTH" in e.message!!, e.message)
    }
}
