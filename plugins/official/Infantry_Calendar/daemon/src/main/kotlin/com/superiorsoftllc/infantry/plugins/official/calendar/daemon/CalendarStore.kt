// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Who made an item: the user (kind `user`) or an agent (kind `agent`, with the session / agent id / role the tool call reported). */
data class CalendarCreator(val kind: String, val agent: String? = null, val session: String? = null, val role: String? = null) {
    companion object { val USER = CalendarCreator("user") }
}

data class CalendarItem(
    val id: String,
    val projectId: String?,
    val kind: String,
    val title: String,
    val notes: String?,
    val allDay: Boolean,
    val startAt: Long?,
    val day: String,
    val dueAt: Long?,
    val remindAt: Long?,
    val zone: String,
    val recurrence: String?,
    val status: String,
    val creator: CalendarCreator,
    val target: String?,
    val triggerId: String?,
    val firedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CalendarEvent(val itemId: String, val at: Long, val kind: String, val by: String?, val detail: String?)

/** What to write for a new item; times are already resolved ([startAt] / [dueAt] / [remindAt] epoch ms, [day] in [zone]). */
data class NewItem(
    val kind: String, val title: String, val notes: String? = null, val projectId: String? = null, val allDay: Boolean = false,
    val startAt: Long? = null, val dueAt: Long? = null, val remindAt: Long? = null, val day: String, val zone: String,
    val target: String? = null, val creator: CalendarCreator = CalendarCreator.USER,
)

/** A change: a null field keeps the value. [clearRemind] / [clearTarget] / [clearNotes] set the column to null. */
data class ItemPatch(
    val title: String? = null, val notes: String? = null, val allDay: Boolean? = null, val startAt: Long? = null, val dueAt: Long? = null,
    val day: String? = null, val zone: String? = null, val remindAt: Long? = null, val target: String? = null, val projectId: String? = null,
    val clearRemind: Boolean = false, val clearTarget: Boolean = false, val clearNotes: Boolean = false,
)

data class ItemFilter(
    val from: String? = null, val to: String? = null, val status: String? = null, val kind: String? = null,
    val projectId: String? = null, val createdBy: String? = null, val limit: Int = Int.MAX_VALUE,
)

/**
 * CAL-1: the Calendar tables. [install] is the plugin's install script: `storage.migrate(1, ...)` runs once per schema version, on first
 * load and after an upgrade. Both tables carry the module prefix `infantry_calendar_`; the data stays when the plugin is disabled.
 */
class CalendarStore(private val db: PluginStorage, private val clock: () -> Long = System::currentTimeMillis) {
    fun install() = db.migrate(1, SCHEMA)

    fun add(n: NewItem, eventsKeep: Int): CalendarItem {
        val now = clock()
        val id = "cal-" + UUID.randomUUID().toString().replace("-", "").take(8)
        db.transaction {
            db.execute(
                "INSERT INTO $ITEMS (id, project_id, kind, title, notes, all_day, start_at, day, due_at, remind_at, zone, recurrence, status, " +
                    "created_by_kind, created_by_agent, created_by_session, created_by_role, target, trigger_id, fired_at, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,'open',?,?,?,?,?,NULL,NULL,?,?)",
                listOf(id, n.projectId, n.kind, n.title, n.notes, if (n.allDay) 1L else 0L, n.startAt, n.day, n.dueAt, n.remindAt, n.zone,
                    n.creator.kind, n.creator.agent, n.creator.session, n.creator.role, n.target, now, now),
            )
            log(id, "created", n.creator.agent ?: n.creator.role ?: n.creator.kind, null, eventsKeep)
        }
        return get(id)!!
    }

    fun get(id: String): CalendarItem? = db.query("SELECT * FROM $ITEMS WHERE id = ?", listOf(id)).firstOrNull()?.let(::row)

    fun list(f: ItemFilter = ItemFilter()): List<CalendarItem> {
        val where = mutableListOf<String>(); val args = mutableListOf<Any?>()
        f.from?.let { where += "day >= ?"; args += it }
        f.to?.let { where += "day <= ?"; args += it }
        f.status?.let { where += "status = ?"; args += it }
        f.kind?.let { where += "kind = ?"; args += it }
        f.projectId?.let { where += "project_id = ?"; args += it }
        f.createdBy?.let {
            if (it == "user") where += "created_by_kind = 'user'" else { where += "created_by_agent = ?"; args += it }
        }
        val sql = "SELECT * FROM $ITEMS" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY day, COALESCE(start_at, due_at, 0), created_at LIMIT ?"
        return db.query(sql, args + f.limit.coerceAtMost(Int.MAX_VALUE / 2).toLong()).map(::row)
    }

    /** The items whose alarm is due: open, an alarm set, not yet fired (CAL-3 fires them). */
    fun dueReminders(now: Long): List<CalendarItem> =
        db.query("SELECT * FROM $ITEMS WHERE status = 'open' AND remind_at IS NOT NULL AND remind_at <= ? AND fired_at IS NULL ORDER BY remind_at", listOf(now)).map(::row)

    fun update(id: String, p: ItemPatch, by: String?, eventsKeep: Int): CalendarItem? {
        val cur = get(id) ?: return null
        val sets = mutableListOf("updated_at = ?"); val args = mutableListOf<Any?>(clock())
        fun set(col: String, v: Any?) { sets += "$col = ?"; args += v }
        p.title?.let { set("title", it) }
        if (p.clearNotes) set("notes", null) else p.notes?.let { set("notes", it) }
        p.allDay?.let { set("all_day", if (it) 1L else 0L) }
        p.startAt?.let { set("start_at", it) }
        p.dueAt?.let { set("due_at", it) }
        p.day?.let { set("day", it) }
        p.zone?.let { set("zone", it) }
        if (p.clearRemind) set("remind_at", null) else p.remindAt?.let { set("remind_at", it); set("fired_at", null); if (cur.status == "fired" || cur.status == "missed") set("status", "open") }
        if (p.clearTarget) set("target", null) else p.target?.let { set("target", it) }
        p.projectId?.let { set("project_id", it) }
        db.transaction {
            db.execute("UPDATE $ITEMS SET ${sets.joinToString(", ")} WHERE id = ?", args + id)
            log(id, "edited", by, null, eventsKeep)
        }
        return get(id)
    }

    /** `done` or `cancelled` (also `open` to reopen). */
    fun setStatus(id: String, status: String, by: String?, eventsKeep: Int): CalendarItem? {
        require(status in STATUSES) { "status must be one of ${STATUSES.joinToString(", ")}" }
        get(id) ?: return null
        db.transaction {
            db.execute("UPDATE $ITEMS SET status = ?, updated_at = ? WHERE id = ?", listOf(status, clock(), id))
            log(id, if (status == "cancelled") "cancelled" else status, by, null, eventsKeep)
        }
        return get(id)
    }

    fun delete(id: String): Boolean = db.transaction {
        db.execute("DELETE FROM $EVENTS WHERE item_id = ?", listOf(id))
        db.execute("DELETE FROM $ITEMS WHERE id = ?", listOf(id)) > 0
    }

    /** The explicit wipe (the settings card's "Delete all calendar data"). Returns the items removed. */
    fun clear(): Int = db.transaction {
        db.execute("DELETE FROM $EVENTS")
        db.execute("DELETE FROM $ITEMS")
    }

    fun events(itemId: String): List<CalendarEvent> =
        db.query("SELECT * FROM $EVENTS WHERE item_id = ? ORDER BY at, id", listOf(itemId)).map {
            CalendarEvent(it["item_id"] as String, (it["at"] as Number).toLong(), it["kind"] as String, it["actor"] as String?, it["detail"] as String?)
        }

    fun log(itemId: String, kind: String, by: String?, detail: String?, keep: Int) {
        db.execute("INSERT INTO $EVENTS (item_id, at, kind, actor, detail) VALUES (?,?,?,?,?)", listOf(itemId, clock(), kind, by, detail))
        db.execute(
            "DELETE FROM $EVENTS WHERE item_id = ? AND id NOT IN (SELECT id FROM $EVENTS WHERE item_id = ? ORDER BY id DESC LIMIT ?)",
            listOf(itemId, itemId, keep.toLong()),
        )
    }

    private fun row(r: Map<String, Any?>): CalendarItem {
        fun long(k: String) = (r[k] as Number?)?.toLong()
        return CalendarItem(
            id = r["id"] as String, projectId = r["project_id"] as String?, kind = r["kind"] as String, title = r["title"] as String,
            notes = r["notes"] as String?, allDay = (long("all_day") ?: 0L) != 0L, startAt = long("start_at"), day = r["day"] as String,
            dueAt = long("due_at"), remindAt = long("remind_at"), zone = r["zone"] as String, recurrence = r["recurrence"] as String?,
            status = r["status"] as String,
            creator = CalendarCreator(r["created_by_kind"] as String, r["created_by_agent"] as String?, r["created_by_session"] as String?, r["created_by_role"] as String?),
            target = r["target"] as String?, triggerId = r["trigger_id"] as String?, firedAt = long("fired_at"),
            createdAt = long("created_at")!!, updatedAt = long("updated_at")!!,
        )
    }

    companion object {
        // Built from two parts so PluginToolsDeclaredGuardTest (which reads a whole quoted infantry_* string as a tool name) does not take a table for a tool.
        const val ITEMS = "infantry" + "_calendar_items"
        const val EVENTS = "infantry" + "_calendar_events"
        val STATUSES = listOf("open", "done", "cancelled", "fired", "missed", "snoozed")
        val KINDS = listOf("task", "reminder")

        /** The v1 tables, exactly as CAL-PLAN-1 section 1 names them, with their indexes. `recurrence` is reserved (always null in v1). */
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS $ITEMS (id TEXT NOT NULL PRIMARY KEY, project_id TEXT, kind TEXT NOT NULL CHECK (kind IN ('task','reminder')), " +
                "title TEXT NOT NULL, notes TEXT, all_day INTEGER NOT NULL DEFAULT 0, start_at INTEGER, day TEXT NOT NULL, due_at INTEGER, remind_at INTEGER, " +
                "zone TEXT NOT NULL, recurrence TEXT, status TEXT NOT NULL DEFAULT 'open', created_by_kind TEXT NOT NULL, created_by_agent TEXT, " +
                "created_by_session TEXT, created_by_role TEXT, target TEXT, trigger_id TEXT, fired_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS infantry_calendar_items_day ON $ITEMS(day)",
            "CREATE INDEX IF NOT EXISTS infantry_calendar_items_status_remind ON $ITEMS(status, remind_at)",
            "CREATE INDEX IF NOT EXISTS infantry_calendar_items_project_day ON $ITEMS(project_id, day)",
            "CREATE TABLE IF NOT EXISTS $EVENTS (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id TEXT NOT NULL, at INTEGER NOT NULL, kind TEXT NOT NULL, actor TEXT, detail TEXT)",
            "CREATE INDEX IF NOT EXISTS infantry_calendar_events_item ON $EVENTS(item_id, id)",
        )

        fun dayOf(epochMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()
        fun parseDay(s: String): LocalDate = LocalDate.parse(s)
    }
}
