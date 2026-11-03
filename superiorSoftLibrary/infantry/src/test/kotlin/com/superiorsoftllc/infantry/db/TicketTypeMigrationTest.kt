package com.superiorsoftllc.infantry.db

import com.superiorsoftllc.infantry.backlog.TicketBoard
import app.cash.sqldelight.db.SqlDriver
import com.superiorsoftllc.infantry.db.InfantryDatabaseFactory
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ORIGIN-SCRUB-1b: a database written under the retired ticket types (contract 1.90.0 and
 * earlier) reads back under Infantry's own set once opened, a second open changes nothing, and a request still
 * naming a retired type is stored under its replacement. Temp files only: this never touches a real ~/.infantry.
 */
class TicketTypeMigrationTest {

    private val dir: Path = Files.createTempDirectory("ticket-type-migration")
    private val db: String = dir.resolve("infantry.db").toString()

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** A file as an older build left it: one ticket per retired type, plus an untyped one. */
    private fun seedOldTypes() {
        InfantryDatabaseFactory.open(db).use { h ->
            TicketType.RETIRED.keys.forEachIndexed { i, old ->
                h.driver.exec(
                    "INSERT INTO tickets(id, title, status, position, created_at, updated_at, ticket_type) " +
                        "VALUES ('t$i', 'T', 'todo', $i, 1, 1, '$old')",
                )
            }
            h.driver.exec("INSERT INTO tickets(id, title, status, position, created_at, updated_at) VALUES ('u', 'U', 'todo', 99, 1, 1)")
        }
    }

    /** What the board serves for each seeded ticket, through the daemon's own read path. */
    private fun boardTypes(): List<String?> = SqlInfantryStore.open(db).use { store ->
        val board = TicketBoard(store)
        TicketType.RETIRED.keys.indices.map { board.get("t$it").ticketType } + board.get("u").ticketType
    }

    private val expected: List<String?> = TicketType.RETIRED.values.toList() + null

    @Test
    fun anOldTicketTypeDatabaseReadsBackUnderTheNewTypes() {
        seedOldTypes()
        assertEquals(expected, boardTypes(), "a ticket still carries a retired type")
    }

    @Test
    fun theMigrationIsIdempotent() {
        seedOldTypes()
        val first = boardTypes()
        val second = boardTypes()
        assertEquals(expected, first)
        assertEquals(first, second, "a second open must change nothing")
        InfantryDatabaseFactory.open(db).use { h -> assertEquals(false, TicketTypeMigration.needed(h.driver)) }
    }

    @Test
    fun aRetiredTypeOnInputIsStoredUnderItsReplacement() {
        SqlInfantryStore.open(db).use { store ->
            val board = TicketBoard(store)
            assertEquals(TicketType.DEFECT, board.save(TicketSave(title = "t", ticketType = "Bug")).ticket.ticketType)
            assertEquals(TicketType.HARDENING, board.save(TicketSave(title = "u", ticketType = TicketType.HARDENING)).ticket.ticketType)
        }
    }

    @Test
    fun theNewSetSharesNoSpellingWithTheRetiredOne() {
        assertEquals(emptySet(), TicketType.ALL.toSet() intersect TicketType.RETIRED.keys)
        assertEquals(TicketType.ALL.toSet(), TicketType.RETIRED.values.toSet())
    }

    private fun SqlDriver.exec(sql: String) {
        execute(null, sql, 0)
    }
}
