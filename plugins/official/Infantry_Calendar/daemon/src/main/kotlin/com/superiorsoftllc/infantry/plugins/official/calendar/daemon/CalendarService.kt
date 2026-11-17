// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar.daemon

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The limits (module.json `limits.calendar.*`), read at each call so a changed setting applies at once. */
data class CalendarLimits(val titleMax: Int, val notesMax: Int, val listMax: Int, val eventsKeep: Int) {
    companion object {
        const val DEFAULT_TITLE_MAX_CHARS = 200
        const val DEFAULT_NOTES_MAX_CHARS = 4000
        const val DEFAULT_LIST_MAX_ITEMS = 200
        const val DEFAULT_EVENTS_KEEP = 50

        fun read(config: (String) -> String?) = CalendarLimits(
            titleMax = int(config("limits.calendar.titleMaxChars"), DEFAULT_TITLE_MAX_CHARS),
            notesMax = int(config("limits.calendar.notesMaxChars"), DEFAULT_NOTES_MAX_CHARS),
            listMax = int(config("limits.calendar.listMaxItems"), DEFAULT_LIST_MAX_ITEMS),
            eventsKeep = int(config("limits.calendar.eventsKeepPerItem"), DEFAULT_EVENTS_KEEP),
        )

        private fun int(s: String?, d: Int) = s?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: d
    }
}

/** A request failed validation; [message] is the whole answer. */
class CalendarRefusal(message: String) : IllegalArgumentException(message)

/** The raw fields of an add / update as a tool or frame carries them (everything optional here; [CalendarService] decides what is needed). */
data class ItemInput(
    val kind: String? = null, val title: String? = null, val notes: String? = null, val date: String? = null, val time: String? = null,
    val inMinutes: Long? = null, val allDay: Boolean? = null, val remindAt: String? = null, val remindBefore: Long? = null,
    val target: String? = null, val projectId: String? = null, val zone: String? = null,
)

/**
 * CAL-1: the rules over [CalendarStore]: resolves date / time / inMinutes into epoch ms and the item's day in its zone, picks the alarm,
 * validates the target and the limits, and tells [changed] after every write (the plugin broadcasts `calendar_changed` from it).
 */
class CalendarService(
    private val store: CalendarStore,
    private val limits: () -> CalendarLimits,
    private val defaultZone: () -> ZoneId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionExists: (String) -> Boolean = { true },
    private val changed: (CalendarChange) -> Unit = {},
) {
    fun add(input: ItemInput, creator: CalendarCreator): CalendarItem {
        val lim = limits()
        val kind = input.kind?.trim()?.lowercase() ?: "task"
        if (kind !in CalendarStore.KINDS) throw CalendarRefusal("kind must be task or reminder")
        val title = checkTitle(input.title, lim)
        val notes = checkNotes(input.notes, lim)
        val zone = zoneOf(input.zone)
        val whenAt = resolveWhen(input, zone)
        val target = checkTarget(input.target)
        if (target != null && kind != "reminder") throw CalendarRefusal("only a reminder has a target; make it a reminder, or leave target out")
        val wantsAlarm = kind == "reminder" || input.remindAt != null || input.remindBefore != null
        val remind = if (wantsAlarm) resolveRemind(input, whenAt, zone) else null
        val item = store.add(
            NewItem(
                kind = kind, title = title, notes = notes, projectId = input.projectId?.trim()?.ifEmpty { null }, allDay = whenAt.allDay,
                startAt = if (kind == "reminder") whenAt.at else null, dueAt = if (kind == "task") whenAt.at else null,
                remindAt = remind, day = whenAt.day, zone = zone.id, target = target, creator = creator,
            ),
            lim.eventsKeep,
        )
        changed(CalendarChange("added", listOf(item.id), listOf(item.day)))
        return item
    }

    fun update(id: String, input: ItemInput, by: String?): CalendarItem {
        val cur = store.get(id) ?: throw CalendarRefusal("no calendar item '$id'")
        val lim = limits()
        val zone = if (input.zone != null) zoneOf(input.zone) else ZoneId.of(cur.zone)
        val touchesTime = input.date != null || input.time != null || input.inMinutes != null || input.allDay != null
        val whenAt = if (touchesTime) resolveWhen(input, zone, cur) else null
        val remind: Long? = when {
            input.remindAt != null || input.remindBefore != null -> resolveRemind(input, whenAt ?: When(cur.startAt ?: cur.dueAt, cur.day, cur.allDay), zone)
            else -> null
        }
        val target = input.target?.let { if (it.isBlank()) null else checkTarget(it) }
        val patch = ItemPatch(
            title = input.title?.let { checkTitle(it, lim) },
            notes = input.notes?.let { checkNotes(it, lim) },
            allDay = whenAt?.allDay,
            startAt = whenAt?.at?.takeIf { cur.kind == "reminder" },
            dueAt = whenAt?.at?.takeIf { cur.kind == "task" },
            day = whenAt?.day, zone = input.zone?.let { zone.id },
            remindAt = remind,
            target = target,
            clearTarget = input.target != null && input.target.isBlank(),
            projectId = input.projectId?.trim()?.ifEmpty { null },
        )
        if (target != null && cur.kind != "reminder") throw CalendarRefusal("only a reminder has a target")
        val updated = store.update(id, patch, by, lim.eventsKeep) ?: throw CalendarRefusal("no calendar item '$id'")
        changed(CalendarChange("updated", listOf(id), listOf(cur.day, updated.day).distinct()))
        return updated
    }

    fun complete(id: String, status: String?, by: String?): CalendarItem {
        val s = (status ?: "done").trim().lowercase()
        if (s !in listOf("done", "cancelled", "open")) throw CalendarRefusal("status must be done, cancelled or open")
        val item = store.setStatus(id, s, by, limits().eventsKeep) ?: throw CalendarRefusal("no calendar item '$id'")
        changed(CalendarChange("status", listOf(id), listOf(item.day)))
        return item
    }

    fun delete(id: String): Boolean {
        val cur = store.get(id) ?: return false
        val ok = store.delete(id)
        if (ok) changed(CalendarChange("deleted", listOf(id), listOf(cur.day)))
        return ok
    }

    fun clear(): Int = store.clear().also { changed(CalendarChange("cleared", emptyList(), emptyList())) }

    fun list(filter: ItemFilter): List<CalendarItem> = store.list(filter.copy(limit = filter.limit.coerceAtMost(limits().listMax)))
    fun get(id: String): CalendarItem? = store.get(id)
    fun events(id: String): List<CalendarEvent> = store.events(id)

    // ---- time -----------------------------------------------------------------------------------------------------------------------

    private class When(val at: Long?, val day: String, val allDay: Boolean)

    private fun zoneOf(name: String?): ZoneId =
        if (name.isNullOrBlank()) defaultZone() else try { ZoneId.of(name.trim()) } catch (e: DateTimeException) { throw CalendarRefusal("unknown time zone '$name'") }

    /** date + time, or inMinutes from now, or allDay on date (default today); a date alone is all day; nothing at all = today, all day. */
    private fun resolveWhen(i: ItemInput, zone: ZoneId, cur: CalendarItem? = null): When {
        val now = clock()
        i.inMinutes?.let {
            if (it < 0) throw CalendarRefusal("inMinutes must not be negative")
            val at = now + it * MS_PER_MINUTE
            return When(at, CalendarStore.dayOf(at, zone), false)
        }
        val date = try {
            i.date?.trim()?.let(LocalDate::parse) ?: cur?.day?.let(LocalDate::parse) ?: LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
        } catch (e: DateTimeParseException) { throw CalendarRefusal("date must be yyyy-MM-dd") }
        val time = try { i.time?.trim()?.let(LocalTime::parse) } catch (e: DateTimeParseException) { throw CalendarRefusal("time must be HH:mm") }
        val allDay = i.allDay ?: (time == null)
        if (allDay || time == null) return When(null, date.toString(), true)
        return When(date.atTime(time).atZone(zone).toInstant().toEpochMilli(), date.toString(), false)
    }

    /** remindAt (yyyy-MM-ddTHH:mm local, or epoch ms), else remindBefore minutes before the item's time, else the item's own time. */
    private fun resolveRemind(i: ItemInput, w: When, zone: ZoneId): Long {
        i.remindAt?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
            s.toLongOrNull()?.let { return it }
            return try { LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() } catch (e: DateTimeParseException) { throw CalendarRefusal("remindAt must be yyyy-MM-ddTHH:mm or epoch milliseconds") }
        }
        val base = w.at ?: LocalDate.parse(w.day).atTime(DEFAULT_ALL_DAY_REMIND).atZone(zone).toInstant().toEpochMilli()
        val before = i.remindBefore ?: return base
        if (before < 0) throw CalendarRefusal("remindBefore must not be negative")
        return base - before * MS_PER_MINUTE
    }

    private fun checkTitle(t: String?, lim: CalendarLimits): String {
        val s = t?.trim().orEmpty()
        if (s.isEmpty()) throw CalendarRefusal("title is required")
        if (s.length > lim.titleMax) throw CalendarRefusal("title is longer than ${lim.titleMax} characters (setting limits.calendar.titleMaxChars)")
        return s
    }

    private fun checkNotes(n: String?, lim: CalendarLimits): String? {
        val s = n?.trim()?.ifEmpty { null } ?: return null
        if (s.length > lim.notesMax) throw CalendarRefusal("notes are longer than ${lim.notesMax} characters (setting limits.calendar.notesMaxChars)")
        return s
    }

    /** `role:<role>` or `session:<id>`; blank = the user only (null). */
    private fun checkTarget(t: String?): String? {
        val s = t?.trim()?.ifEmpty { null } ?: return null
        when {
            s.startsWith("role:") && s.length > "role:".length -> {}
            s.startsWith("session:") && s.length > "session:".length -> {
                if (!sessionExists(s.removePrefix("session:"))) throw CalendarRefusal("no session '${s.removePrefix("session:")}' to remind")
            }
            else -> throw CalendarRefusal("target must be role:<role> or session:<id> (leave it out to remind the user)")
        }
        return s
    }

    companion object {
        const val MS_PER_MINUTE = 60_000L
        /** An all-day reminder with no alarm time rings at this local time. */
        val DEFAULT_ALL_DAY_REMIND: LocalTime = LocalTime.of(9, 0)
    }
}

/** What a write changed: broadcast to clients as `calendar_changed`. */
data class CalendarChange(val op: String, val ids: List<String>, val days: List<String>)
