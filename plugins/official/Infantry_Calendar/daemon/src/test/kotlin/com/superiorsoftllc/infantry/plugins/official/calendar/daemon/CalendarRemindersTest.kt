// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar.daemon

import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationActionEvent
import java.io.File
import java.sql.DriverManager
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An in-memory Triggers service: one row per trigger, keyed by id, holding the item as it was last synced. */
internal class FakeTriggers : ReminderTriggers {
    val rows = LinkedHashMap<String, CalendarItem>()
    private var n = 0
    override fun upsert(item: CalendarItem, existingId: String?): String {
        val id = existingId?.takeIf { it in rows } ?: "trg-${++n}"
        rows[id] = item
        return id
    }
    override fun delete(id: String) { rows.remove(id) }
    override fun owned() = rows.map { (id, item) -> OwnedTrigger(id, item.id, "armed") }
}

/** CAL-3: clock-injected scheduler tests (due fires once, restart late vs missed, trigger lifecycle, reconcile, no Triggers, snooze). */
class CalendarRemindersTest {
    private val file = File.createTempFile("calendar-rem", ".db").also { it.deleteOnExit() }
    private val conns = mutableListOf<java.sql.Connection>()
    private var now = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private val zone = ZoneId.of("UTC")
    private val fake = FakeTriggers()
    private var triggersLoaded = true
    private val notices = mutableListOf<Notification>()
    private val events = mutableListOf<Pair<String, String>>()
    private val opened = mutableListOf<String>()

    private inner class Daemon {
        val store = CalendarStore(JdbcStorage(DriverManager.getConnection("jdbc:sqlite:${file.path}").also { conns += it })) { now }.also { it.install() }
        val service = CalendarService(
            store, { CalendarLimits(200, 4000, 200, 50) }, { zone }, { now },
            triggers = { if (triggersLoaded) fake else null },
            publish = { name, item -> events += name to item.id },
        )
        val reminders = CalendarReminders(store, service, { now }, { 30 * CalendarService.MS_PER_MINUTE }, notify = { notices += it }, openItem = { opened += it })
    }

    /** One daemon run; [restart] is a new process over the same database file. */
    private var d = Daemon()
    private fun restart() { d = Daemon() }

    @AfterTest fun close() { conns.forEach { it.close() } }

    private fun minutes(m: Long) { now += m * CalendarService.MS_PER_MINUTE }
    private fun timer(title: String, inMinutes: Long, target: String? = null) =
        d.service.add(ItemInput(kind = "reminder", title = title, inMinutes = inMinutes, target = target), CalendarCreator.USER)

    @Test fun `a due reminder fires once and posts one notification with its buttons`() {
        val r = timer("Stretch", 2)
        assertEquals(emptyList(), d.reminders.tick().fired)
        minutes(2)
        assertEquals(listOf(r.id), d.reminders.tick().fired)
        assertEquals(emptyList(), d.reminders.tick().fired, "a second tick must not fire it again")
        assertEquals(1, notices.size)
        assertEquals("Stretch", notices.single().title)
        assertTrue(notices.single().actions.map { it.id }.containsAll(listOf("open", "snooze.5m", "snooze.1h", "snooze.tomorrow", "done")))
        assertEquals("fired", d.store.get(r.id)!!.status)
        assertTrue(CalendarService.EVENT_CREATED to r.id in events)
        assertTrue(CalendarService.EVENT_FIRED to r.id in events)
    }

    @Test fun `a task alarm fires but the task stays open`() {
        val t = d.service.add(ItemInput(kind = "task", title = "Pay rent", inMinutes = 1, remindBefore = 0), CalendarCreator.USER)
        minutes(1)
        assertEquals(listOf(t.id), d.reminders.tick().fired)
        assertEquals("open", d.store.get(t.id)!!.status)
        assertNotNull(d.store.get(t.id)!!.firedAt)
    }

    @Test fun `after a restart a slightly late alarm fires once and a long-late one is missed with one summary`() {
        val late = timer("Late", 1)
        val gone1 = timer("Gone 1", 2)
        val gone2 = timer("Gone 2", 3)
        // "Late" rings 2 h from now; the daemon is off for 130 min: "Late" is 10 min late, the other two are over 2 h late.
        d.service.update(late.id, ItemInput(remindAt = (now + 120 * CalendarService.MS_PER_MINUTE).toString()), "user")
        minutes(130)
        restart()
        val r = d.reminders.tick()
        assertEquals(listOf(late.id), r.fired)
        assertEquals(setOf(gone1.id, gone2.id), r.missed.toSet())
        assertEquals("missed", d.store.get(gone1.id)!!.status)
        assertEquals(2, notices.size, "one notice for the fired alarm, ONE summary for both misses")
        assertTrue(notices.any { it.title == "2 reminders were missed" })
        restart()
        assertEquals(TickResult(emptyList(), emptyList()), d.reminders.tick(), "a second restart fires nothing again")
    }

    @Test fun `an agent reminder owns one trigger that moves and goes with the item`() {
        val r = timer("Check the gate", 2, target = "role:qa")
        val tid = assertNotNull(d.store.get(r.id)!!.triggerId)
        assertEquals(r.remindAt, fake.rows[tid]!!.remindAt)
        assertEquals("role:qa", fake.rows[tid]!!.target)

        val moved = d.service.update(r.id, ItemInput(remindAt = (now + 10 * CalendarService.MS_PER_MINUTE).toString()), "user")
        assertEquals(tid, moved.triggerId, "the same trigger is updated")
        assertEquals(moved.remindAt, fake.rows[tid]!!.remindAt)

        d.service.update(r.id, ItemInput(target = "role:pm"), "user")
        assertEquals("role:pm", fake.rows[tid]!!.target)

        d.service.complete(r.id, "done", "user")
        assertTrue(fake.rows.isEmpty(), "a closed item drops its trigger")
        assertNull(d.store.get(r.id)!!.triggerId)

        val r2 = timer("Again", 5, target = "role:qa")
        assertEquals(1, fake.rows.size)
        d.service.delete(r2.id)
        assertTrue(fake.rows.isEmpty(), "a deleted item drops its trigger")
    }

    @Test fun `a fired agent reminder keeps its trigger for Triggers to deliver, a missed one drops it`() {
        val fired = timer("On time", 1, target = "role:qa")
        val missed = timer("Too late", 1, target = "session:s1")
        d.service.update(missed.id, ItemInput(remindAt = (now - 120 * CalendarService.MS_PER_MINUTE).toString()), "user")
        minutes(1)
        val r = d.reminders.tick()
        assertEquals(listOf(fired.id), r.fired)
        assertEquals(listOf(missed.id), r.missed)
        assertEquals(setOf(d.store.get(fired.id)!!.triggerId), fake.rows.keys, "only the fired item's trigger is left")
        assertTrue(notices.first().body.contains("also sent to role:qa"))
    }

    @Test fun `reconcile recreates a missing trigger and deletes an orphan`() {
        val r = timer("Standup", 30, target = "role:qa")
        fake.rows.clear() // Triggers lost it (e.g. it was not loaded when the item changed)
        fake.rows["trg-orphan"] = r.copy(id = "cal-deleted0")
        restart()
        val (created, deleted) = d.reminders.reconcile()
        assertEquals(1, created)
        assertEquals(1, deleted)
        val tid = assertNotNull(d.store.get(r.id)!!.triggerId)
        assertEquals(setOf(tid), fake.rows.keys)
        assertEquals(0 to 0, d.reminders.reconcile(), "a second pass changes nothing")
    }

    @Test fun `without the Triggers plugin agent targets are refused and user reminders still work`() {
        triggersLoaded = false
        val e = assertFailsWith<CalendarRefusal> { timer("For QA", 2, target = "role:qa") }
        assertTrue(e.message!!.contains("Triggers"))
        assertTrue(d.service.list(ItemFilter()).isEmpty(), "a refused add leaves no row")
        val mine = timer("For me", 2)
        minutes(2)
        assertEquals(listOf(mine.id), d.reminders.tick().fired)
        assertEquals(0 to 0, d.reminders.reconcile())
    }

    @Test fun `snooze rings again later and the notification buttons drive it`() {
        val r = timer("Tea", 1)
        minutes(1)
        d.reminders.tick()
        val noticeId = notices.single().id
        d.reminders.onAction(NotificationActionEvent(noticeId, "snooze.5m"))
        assertEquals("snoozed", d.store.get(r.id)!!.status)
        minutes(4)
        assertEquals(emptyList(), d.reminders.tick().fired)
        minutes(1)
        assertEquals(listOf(r.id), d.reminders.tick().fired)

        d.reminders.onAction(NotificationActionEvent(notices.last().id, "open"))
        assertEquals(listOf(r.id), opened)

        val tomorrow = d.service.snooze(r.id, "tomorrow", "user")
        assertEquals("2023-11-15T09:00:00Z", java.time.Instant.ofEpochMilli(tomorrow.remindAt!!).toString())

        d.reminders.onAction(NotificationActionEvent(notices.last().id, "done"))
        assertEquals("done", d.store.get(r.id)!!.status)
        assertTrue(CalendarService.EVENT_DONE to r.id in events)
        assertFailsWith<CalendarRefusal> { d.service.snooze(r.id, "5m", "user") }
    }

    @Test fun `snoozing a fired agent reminder gives it a fresh trigger`() {
        val r = timer("Ping QA", 1, target = "role:qa")
        minutes(1)
        d.reminders.tick()
        fake.rows.clear() // Triggers fired it and deleted it (deleteAfterFire)
        val s = d.service.snooze(r.id, "1h", "user")
        val tid = assertNotNull(s.triggerId)
        assertEquals(s.remindAt, fake.rows[tid]!!.remindAt)
    }
}
