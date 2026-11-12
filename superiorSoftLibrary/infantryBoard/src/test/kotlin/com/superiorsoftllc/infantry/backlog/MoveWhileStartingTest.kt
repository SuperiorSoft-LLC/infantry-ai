// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardAgent
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.IN_PROGRESS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TICKET-MOVE-HANG-1: a ticket start holds the board while git and the session launch run, so a move (or read) of ANOTHER ticket
 * waits for the whole start and its answer arrives late. A move must answer within its budget while a start is still running.
 */
class MoveWhileStartingTest {

    @Test
    fun aMoveAnswersWhileAnotherTicketIsStarting() {
        val host = InMemoryBoardHost()
        val worker = host.agents.save(BoardAgent(name = "Backend", role = "backend"))
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(host, idFactory = { ids.next() }, profilesAvailable = true)
        val starting = board.save(TicketSave(title = "starts", agentId = worker.id)).ticket
        val other = board.save(TicketSave(title = "moves", agentId = worker.id)).ticket

        val inStart = CountDownLatch(1)
        val release = CountDownLatch(1)
        host.onStart = { inStart.countDown(); release.await(30, TimeUnit.SECONDS) }
        val starter = Thread { board.move(TicketMove(starting.id, IN_PROGRESS)) }.apply { start() }
        try {
            assertTrue(inStart.await(10, TimeUnit.SECONDS), "the start reached the session launch")
            var movedMs = -1L
            val mover = Thread {
                val t0 = System.nanoTime()
                board.move(TicketMove(other.id, position = 0))
                movedMs = (System.nanoTime() - t0) / 1_000_000
            }.apply { start() }
            mover.join(3_000)
            val answered = movedMs >= 0
            release.countDown()
            mover.join(10_000)
            assertTrue(answered, "the move of another ticket answered while a start was still running (it waited $movedMs ms after release)")
        } finally {
            release.countDown()
            starter.join(10_000)
        }
    }
}
