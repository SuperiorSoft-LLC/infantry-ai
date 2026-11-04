// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.TicketServices
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus.DONE
import com.superiorsoftllc.infantry.protocol.TicketStatus.TODO
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BOARD-REENTRY-1: a ticket move answers once it committed; its ticket events reach their consumers AFTER the board lets go.
 * The listener here does what a lane hook does through a plugin: it asks the board for the ticket from ANOTHER thread and waits
 * (the plugin guard's pool), so delivery under the board's monitor would park the move until that wait gives up.
 */
class BoardReentryTest {
    private val dir: File = Files.createTempDirectory("infantry-board-reentry").toFile()
    private val opened = mutableListOf<SqlInfantryStore>()
    private val hookPool = Executors.newSingleThreadExecutor { r -> Thread(r, "test-hook").apply { isDaemon = true } }

    @AfterTest
    fun cleanUp() {
        hookPool.shutdownNow()
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    @Test
    fun `a move answers at once while a lane hook reads the moved ticket back from the board`() {
        val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath).also { opened += it }
        val board = TicketBoard(store)
        val log = TicketServices.eventLog(store)
        board.events = log
        val t = board.save(TicketSave(title = "one")).ticket

        val hookRead = CountDownLatch(1)
        var hookSaw: String? = null
        log.subscribe("test:lane-hook") { e ->
            if (e.isTransition && e.to == DONE) {
                // The hook's ticket_get: another thread, bounded wait (the guard's slow mark is 2 s).
                hookSaw = hookPool.submit<String> { board.get(e.ticketId).status }.get(2, TimeUnit.SECONDS)
                hookRead.countDown()
            }
        }

        val began = System.nanoTime()
        val answer = board.move(TicketMove(t.id, DONE, null, false))
        val tookMs = (System.nanoTime() - began) / 1_000_000

        assertEquals(DONE, answer.ticket.status)
        assertTrue(tookMs < 200, "the move answered in $tookMs ms; its hooks must not run inside it")
        assertTrue(hookRead.await(5, TimeUnit.SECONDS), "the lane hook ran and its board read returned")
        assertEquals(DONE, hookSaw, "the hook read the committed move")
    }

    @Test
    fun `events still reach a consumer in seq order when delivered off the board`() {
        val store = SqlInfantryStore.open(File(dir, "order.db").absolutePath).also { opened += it }
        val board = TicketBoard(store)
        val log = TicketServices.eventLog(store)
        board.events = log
        val seen = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val all = CountDownLatch(6)
        log.subscribe("test:order") { seen += it.seq; all.countDown() }
        val a = board.save(TicketSave(title = "a")).ticket
        val b = board.save(TicketSave(title = "b")).ticket
        board.move(TicketMove(a.id, DONE, null, false))
        board.move(TicketMove(b.id, DONE, null, false))
        board.move(TicketMove(a.id, TODO, null, false))
        board.move(TicketMove(b.id, TODO, null, false))
        assertTrue(all.await(5, TimeUnit.SECONDS), "all six events arrived: $seen")
        assertEquals(seen.sorted(), seen.toList(), "delivered in seq order")
    }
}
