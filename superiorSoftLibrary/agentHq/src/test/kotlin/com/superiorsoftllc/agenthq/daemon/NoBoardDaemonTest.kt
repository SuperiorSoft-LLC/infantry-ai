package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DP-5h-3d-1: a daemon built with no board. [AgentHqDaemon.board] is null; the project frames still work; every
 * ticket frame answers `<type> refused: this daemon has no board`.
 */
class NoBoardDaemonTest {
    @Test
    fun `a daemon with no board serves projects and refuses ticket frames`() {
        val fixture = DaemonFixture.launch(withBoard = false)
        try {
            fixture.client.await(Envelope.READY)
            assertNull(fixture.daemon.boardPorts.ops(), "no board is attached")

            fixture.send(Envelope.PROJECT_SAVE, """{"name":"NoBoardProject"}""")
            val project = fixture.client.awaitWhere("project saved") { it.type == Envelope.PROJECT && it.data.contains("NoBoardProject") }
            assertTrue(project.data.contains("NoBoardProject"))

            val already = fixture.client.of(Envelope.ERROR).size
            fixture.send(Envelope.TICKET_SAVE, """{"title":"x"}""")
            val refused = fixture.client.await(Envelope.ERROR, occurrence = already, timeoutMs = 4_000)
            assertEquals("ticket_save refused: this daemon has no board", refused.data)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun `core consumers of the board answer no board without failing`() {
        val fixture = DaemonFixture.launch(withBoard = false)
        try {
            fixture.client.await(Envelope.READY)
            // roster titles (ticket titles by session) come from the board port: none, and the frame still answers
            fixture.send(Envelope.AGENT_ROSTER, """{"projectId":"p-none"}""")
            val roster = fixture.client.await(Envelope.ROSTER, timeoutMs = 4_000)
            assertTrue(roster.data.startsWith("["))
            // the handover and the first-turn brief hold no board: the brief is null, the daemon stays up
            assertNull(fixture.daemon.boardPorts.ops())
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
