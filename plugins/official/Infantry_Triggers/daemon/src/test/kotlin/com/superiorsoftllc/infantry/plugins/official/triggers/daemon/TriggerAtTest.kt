// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.triggers.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerSaveRequest
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.TriggerSave
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TRIG-AT-1: the one-shot kind `at`, owner/ref persistence and the orphan prune, on [StorageTriggerStore] over a core-created
 * database file (so the plugin's schema migration runs on the adopted table, and a "restart" is a second service on the same file).
 * The clock is a seam ([now]); the pump is driven by hand.
 */
class TriggerAtTest {
    /** A PluginStorage over one SQLite connection that records migrated versions, like the host's plugin_storage_versions. */
    private class VersionedStorage(private val c: Connection) : PluginStorage {
        private val done = HashSet<Int>()
        override fun migrate(version: Int, sql: List<String>) {
            if (!done.add(version)) return
            sql.forEach { c.createStatement().use { s -> s.execute(it) } }
        }
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = synchronized(c) {
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeQuery().use { rs ->
                    val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                    generateSequence { if (rs.next()) cols.associateWith { k -> rs.getObject(k) } else null }.toList()
                }
            }
        }
        override fun execute(sql: String, args: List<Any?>): Int = synchronized(c) {
            c.prepareStatement(sql).use { ps ->
                args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
                ps.executeUpdate()
            }
        }
        override fun <T> transaction(block: () -> T): T = synchronized(c) { block() }
    }

    private val dir: Path = Files.createTempDirectory("trig-at")
    private val conn: Connection
    private val storage: VersionedStorage
    private val services = mutableListOf<TriggerService>()

    init {
        SqlInfantryStore.open(dir.resolve("d.db").toString()).close() // core creates the tables, as the daemon does
        conn = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("d.db")}")
        storage = VersionedStorage(conn)
    }

    @AfterTest fun cleanup() {
        services.forEach { runCatching { it.close() } }
        conn.close()
    }

    @Volatile private var now = 0L
    private val starts = AtomicInteger(0)

    /** A service on the shared file: a new call is a daemon restart (a new store reads the rows back). */
    private fun service(maxLateMs: Long = 900_000L): TriggerService = TriggerService(
        repo = StorageTriggerStore(storage),
        attention = MemAttention(),
        startNext = { starts.incrementAndGet(); null }, // nothing to start: the run ends at once as nothing_to_start
        sessions = { null },
        clock = { now },
        pumpMs = 3_600_000, // never ticks by itself: the pump is driven by hand
        autoStart = false,
        atMaxLateMs = { maxLateMs },
    ).also { services += it }

    private fun at(fireAt: Long?, deleteAfterFire: Boolean = false, name: String = "one-shot") =
        TriggerSave(name = name, kind = "at", fireAt = fireAt, deleteAfterFire = deleteAfterFire, coalesceMs = 0)

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `an at trigger fires once at fireAt, then is disabled`() {
        val s = service()
        val t = s.save(at(100_000))
        assertEquals(100_000L, t.nextFireAt)
        now = 99_999
        s.pumpWindows()
        assertTrue(s.events(t.id).isEmpty(), "not due yet")
        now = 100_000
        s.pumpWindows()
        awaitTrue { starts.get() == 1 }
        now = 200_000
        s.pumpWindows(); s.pumpWindows()
        val events = s.events(t.id)
        assertEquals(1, events.size, "fired exactly once: $events")
        assertEquals(TriggerService.AT_SOURCE, events.single().source)
        val after = s.get(t.id)!!
        assertEquals("disabled", after.state)
        assertFalse(after.enabled)
        assertNull(after.nextFireAt, "the slot is spent")
        assertEquals(100_000L, after.fireAt)
        assertEquals(1, starts.get())
    }

    @Test
    fun `deleteAfterFire removes the one-shot once its run is over, and the clients are told`() {
        val s = service()
        val deleted = mutableListOf<String>()
        s.deletedSink = { deleted += it }
        val t = s.save(at(50_000, deleteAfterFire = true))
        now = 50_000
        s.pumpWindows()
        awaitTrue { s.pumpWindows(); s.get(t.id) == null }
        assertEquals(listOf(t.id), deleted)
        assertEquals(1, starts.get())
    }

    @Test
    fun `a slot passed while down fires once when within the cap, and a restart never fires it again`() {
        val first = service()
        val t = first.save(at(60_000))
        first.close()
        now = 60_000 + 500_000 // half the default cap late
        val second = service()
        second.pumpWindows()
        awaitTrue { starts.get() == 1 }
        assertEquals(listOf("at"), second.events(t.id).map { it.source })
        second.close()
        now = 2_000_000
        val third = service()
        third.pumpWindows(); third.pumpWindows()
        assertEquals(1, third.events(t.id).size, "a fired one-shot does not fire after another restart")
        assertEquals(1, starts.get())
    }

    @Test
    fun `a slot later than the cap is recorded missed and does not fire, nor is it deleted`() {
        val first = service()
        val t = first.save(at(60_000, deleteAfterFire = true))
        first.close()
        now = 60_000 + 900_001
        val second = service(maxLateMs = 900_000)
        second.pumpWindows(); second.pumpWindows()
        val events = second.events(t.id)
        assertEquals(listOf("missed"), events.map { it.disposition })
        assertEquals(0, starts.get())
        val kept = assertNotNull(second.get(t.id), "a missed one-shot never fired, so it is kept for its owner to see")
        assertEquals("disabled", kept.state)
        assertNull(kept.nextFireAt)
    }

    @Test
    fun `no hourly ceiling applies to a one-shot`() {
        val s = service()
        val t = s.save(at(10_000).copy(ceilingRuns = 1, coalesceMs = 600_000))
        now = 10_000
        s.pumpWindows()
        // Started at once (never held in the 10-minute window) and never refused by the 1-run ceiling.
        awaitTrue { s.events(t.id).map { it.disposition } == listOf("nothing_to_start") }
        assertEquals(1, starts.get())
        assertFalse(s.get(t.id)!!.state == "tripped")
    }

    @Test
    fun `at needs fireAt, and fireAt or deleteAfterFire are refused on other kinds`() {
        val s = service()
        assertFailsWith<IllegalArgumentException> { s.save(at(null)) }
        assertFailsWith<IllegalArgumentException> { s.save(TriggerSave(name = "m", kind = "manual", fireAt = 5)) }
        assertFailsWith<IllegalArgumentException> { s.save(TriggerSave(name = "m", kind = "manual", deleteAfterFire = true)) }
        assertFailsWith<IllegalArgumentException> { s.save(at(5).copy(intervalMs = 60_000)) }
    }

    @Test
    fun `editing a fired one-shot to a new fireAt schedules it again, a rename does not`() {
        val s = service()
        val t = s.save(at(10_000))
        now = 10_000
        s.pumpWindows()
        awaitTrue { starts.get() == 1 }
        val renamed = s.save(at(10_000, name = "renamed").copy(id = t.id))
        assertNull(renamed.nextFireAt, "same fireAt: still spent")
        val moved = s.save(at(90_000).copy(id = t.id))
        assertEquals(90_000L, moved.nextFireAt)
        assertEquals("armed", moved.state)
        now = 90_000
        s.pumpWindows()
        awaitTrue { starts.get() == 2 }
    }

    @Test
    fun `the owner is stamped by the service, persisted with ref, and a forged owner is refused`() {
        val s = service()
        val scoped = ScopedTriggers(s, "Infantry_Calendar")
        val saved = scoped.save(TriggerSaveRequest(name = "standup", kind = "at", fireAt = 1_000, coalesceMs = 0, ref = "event-42"))
        assertEquals("Infantry_Calendar", saved.owner)
        assertEquals("event-42", saved.ref)
        val reread = service().get(saved.id)!! // a new store over the same file
        assertEquals("Infantry_Calendar", reread.owner)
        assertEquals("event-42", reread.ref)
        assertEquals(1_000L, reread.fireAt)

        // A module cannot claim another module's name; a person (frame, capability) cannot claim any owner.
        assertFailsWith<IllegalArgumentException> { scoped.save(TriggerSaveRequest(name = "x", owner = "Infantry_Other")) }
        assertFailsWith<IllegalArgumentException> { s.save(TriggerSave(name = "x", owner = "Infantry_Calendar")) }
        assertFailsWith<IllegalArgumentException> { s.save(TriggerSave(name = "x", owner = "Infantry_Other"), owner = "Infantry_Calendar") }
        // Only the owner edits: not a person, not another module; and a module cannot take over a person's trigger.
        assertFailsWith<IllegalArgumentException> { s.save(TriggerSave(id = saved.id, name = "hijack", kind = "at", fireAt = 1_000)) }
        assertFailsWith<IllegalArgumentException> { ScopedTriggers(s, "Infantry_Other").save(TriggerSaveRequest(id = saved.id, name = "hijack", kind = "at", fireAt = 1_000)) }
        val mine = s.save(TriggerSave(name = "mine"))
        assertNull(mine.owner)
        assertFailsWith<IllegalArgumentException> { s.save(TriggerSave(id = mine.id, name = "taken"), owner = "Infantry_Calendar") }
        assertFailsWith<IllegalArgumentException> { s.requireOwner(s.get(saved.id)!!, null) }
        // The owner edits its own.
        assertEquals("standup 2", scoped.save(TriggerSaveRequest(id = saved.id, name = "standup 2", kind = "at", fireAt = 2_000, coalesceMs = 0)).name)
    }

    @Test
    fun `orphan prune drops only the triggers of modules no longer loaded`() {
        val s = service()
        val gone = s.save(TriggerSave(name = "g"), owner = "Infantry_Gone")
        val here = s.save(TriggerSave(name = "h"), owner = "Infantry_Here")
        val person = s.save(TriggerSave(name = "p"))
        val told = mutableListOf<String>()
        s.deletedSink = { told += it }
        assertEquals(listOf(gone.id), s.pruneOrphans(setOf("Infantry_Here", "Infantry_Triggers")))
        assertEquals(listOf(gone.id), told)
        assertNull(s.get(gone.id))
        assertNotNull(s.get(here.id))
        assertNotNull(s.get(person.id))
        assertEquals(emptyList(), s.pruneOrphans(setOf("Infantry_Here")), "nothing more to prune")
    }
}
