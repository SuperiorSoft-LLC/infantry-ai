// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHarness
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire
import com.superiorsoftllc.infantry.settings.Placement
import com.superiorsoftllc.infantry.settings.PlacementResult
import com.superiorsoftllc.infantry.settings.RunnerEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNNER-UPDATE-1: a runner older than the app is read `outdated` at pairing and on every heartbeat, and a start on it is refused up
 * front with the fix, instead of failing halfway with "not a runner link operation" (the owner's 1.8.0 runner against a 1.9.0 app).
 */
class RunnerOutdatedTest {
    private class FakeRemote(var contract: String) : RunnerLink {
        var starts = 0
        override val minPeerContract: String get() = RunnerLinkWire.MIN_RUNNER_CONTRACT
        override fun ticketIdOf(sessionId: String): String? = null
        override fun hello() = OrchestratorReply.Ok(RunnerInfo("r1", contract, listOf(RunnerHarness("pi", loggedIn = true))))
        override fun health() = OrchestratorReply.Ok(RunnerHealth(RunnerHealth.UP))
        override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> { starts++; return OrchestratorReply.Ok(SessionRef("s-1")) }
    }

    private val local = object : RunnerLink {
        override fun ticketIdOf(sessionId: String): String? = null
    }

    private fun hub(remote: FakeRemote) = RunnerHub(local, remotes = { listOf("r1") }).also { h -> h.register(RunnerHub.REMOTE_BINDING) { _, _ -> remote } }

    @Test
    fun `an older runner reads outdated and its start is refused before it reaches the runner`() {
        val remote = FakeRemote("1.8.0")
        val pool = hub(remote)
        pool.heartbeat(60_000)
        assertEquals(RunnerHealth.OUTDATED, pool.runners().first { it.name == "r1" }.state)
        val refusal = pool.outdatedRefusal("r1")
        assertEquals(
            "runner 'r1' is older than the app (contract 1.8.0, the app needs ${RunnerLinkWire.MIN_RUNNER_CONTRACT} or newer): update it: Settings > Runners > Update runner",
            refusal,
        )
        val reply = pool.link("r1")!!.startSession(StartSpec("t-1")) as OrchestratorReply.Ok
        assertEquals(refusal, reply.value.refusal)
        assertEquals(0, remote.starts, "the runner is never asked to start")
    }

    @Test
    fun `the next heartbeat after an update reads it up again (every reconnect is checked)`() {
        val remote = FakeRemote("1.8.0")
        val pool = hub(remote)
        pool.heartbeat(60_000)
        remote.contract = RunnerLinkWire.CONTRACT
        pool.heartbeat(60_000)
        assertEquals(RunnerHealth.UP, pool.runners().first { it.name == "r1" }.state)
        assertNull(pool.outdatedRefusal("r1"))
        val reply = pool.link("r1")!!.startSession(StartSpec("t-1")) as OrchestratorReply.Ok
        assertEquals("s-1", reply.value.sessionId)
    }

    @Test
    fun `placement refuses an outdated runner with the same fix`() {
        val r = Placement.pick("r1", null, "local", listOf(RunnerEntry("local"), RunnerEntry("r1", health = RunnerHealth.OUTDATED)))
        assertEquals("runner r1 is older than the app: update it: Settings > Runners > Update runner", (r as PlacementResult.Refused).reason)
        assertEquals(RunnerLinkWire.OUTDATED_HINT, Placement.OUTDATED_FIX, "the board and the API name the fix in the same words")
        assertEquals(RunnerEntry.HEALTH_OUTDATED, RunnerHealth.OUTDATED)
    }

    @Test
    fun `contract comparison is numeric and never refuses on an unreadable answer`() {
        assertTrue(RunnerLinkWire.contractOlder("1.8.0", "1.11.0"))
        assertTrue(RunnerLinkWire.contractOlder("1.11.0", "1.11.1"))
        assertFalse(RunnerLinkWire.contractOlder("1.11.0", "1.11.0"))
        assertFalse(RunnerLinkWire.contractOlder("1.12.0", "1.11.0"))
        assertFalse(RunnerLinkWire.contractOlder("2.0.0", "1.11.0"))
        assertFalse(RunnerLinkWire.contractOlder("", "1.11.0"))
        assertFalse(RunnerLinkWire.contractOlder("dev", "1.11.0"))
        assertFalse(RunnerLinkWire.contractOlder(null, "1.11.0"))
    }
}
