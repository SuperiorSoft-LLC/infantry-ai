// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNNER-POOL-10 (ticket 5690d5b1): the deployment mode switch. Unit rules for the choice, then two real in-process daemons (port 0):
 * one `both` (today's behaviour, board answers) and one `runner` (the board frames are refused with the reason).
 */
class DeploymentModeTest {

    @Test
    fun `no source names both, the first non-blank source wins, and a bad value is refused`() {
        assertEquals(DeploymentMode.BOTH, DeploymentMode.resolve(null, "", " "))
        assertEquals(DeploymentMode.RUNNER, DeploymentMode.resolve("runner", "orchestrator", "both"))
        assertEquals(DeploymentMode.ORCHESTRATOR, DeploymentMode.resolve(null, "Orchestrator", "runner"))
        assertEquals(DeploymentMode.RUNNER, DeploymentMode.resolve(null, null, "runner"))
        val e = assertFailsWith<IllegalArgumentException> { DeploymentMode.resolve("server", null, null) }
        assertTrue("both | runner | orchestrator" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `the command line carries --mode in both spellings`() {
        assertEquals("runner", DeploymentMode.fromArgs(arrayOf("--mode", "runner")))
        assertEquals("runner", DeploymentMode.fromArgs(arrayOf("--mode=runner")))
        assertNull(DeploymentMode.fromArgs(arrayOf("--other")))
    }

    @Test
    fun `only a runner refuses board frames, and both and orchestrator refuse none`() {
        assertNotNull(DeploymentMode.RUNNER.refusal(Envelope.TICKET_LIST, null))
        assertNotNull(DeploymentMode.RUNNER.refusal("anything", "orchestrator"))
        assertNull(DeploymentMode.RUNNER.refusal(Envelope.PROMPT, "runner"))
        assertNull(DeploymentMode.BOTH.refusal(Envelope.TICKET_LIST, "orchestrator"))
        assertNull(DeploymentMode.ORCHESTRATOR.refusal(Envelope.TICKET_LIST, "orchestrator"))
        assertTrue(DeploymentMode.BOTH.runsSessions && DeploymentMode.BOTH.hasBoard)
        assertTrue(!DeploymentMode.ORCHESTRATOR.runsSessions && DeploymentMode.ORCHESTRATOR.hasBoard)
        assertTrue(DeploymentMode.RUNNER.runsSessions && !DeploymentMode.RUNNER.hasBoard)
    }

    @Test
    fun `a runner-mode daemon refuses the board while a both-mode daemon answers it`() {
        val both = DaemonFixture.launch(mode = DeploymentMode.BOTH)
        val runner = DaemonFixture.launch(mode = DeploymentMode.RUNNER)
        try {
            assertEquals(DeploymentMode.BOTH, both.daemon.deploymentMode)
            assertEquals(DeploymentMode.RUNNER, runner.daemon.deploymentMode)

            both.send(Envelope.TICKET_LIST, "")
            both.client.await(Envelope.TICKETS)

            runner.send(Envelope.TICKET_LIST, "")
            val refused = runner.client.await(Envelope.ERROR)
            assertTrue("runner mode" in refused.data, refused.data)
            // The runner side still answers: a frame the runner owns is not refused.
            assertNull(DeploymentMode.RUNNER.refusal(Envelope.PROMPT, null))
        } finally {
            runner.stop()
            both.stop()
        }
    }

    @Test
    fun `an orchestrator-mode daemon refuses to start a local session`() {
        val orch = DaemonFixture.launch(mode = DeploymentMode.ORCHESTRATOR)
        try {
            val why = orch.daemon.manager.spawnGate("backend", null, null)
            assertTrue(why != null && "orchestrator mode" in why, why)
        } finally {
            orch.stop()
        }
    }
}
