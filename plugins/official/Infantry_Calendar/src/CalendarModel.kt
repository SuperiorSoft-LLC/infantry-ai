// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One entry of an item's history, as the daemon answers it. */
data class CalHistory(val at: Long, val kind: String, val by: String?, val detail: String?)

/** One calendar item as `calendar/list` and `calendar/get` answer it. [day] is the item's own day in its [zone]. */
data class CalItem(
    val id: String, val kind: String, val title: String, val status: String, val day: String, val allDay: Boolean, val zone: String,
    val notes: String?, val startAt: Long?, val dueAt: Long?, val remindAt: Long?, val target: String?, val createdBy: String,
    val history: List<CalHistory> = emptyList(),
) {
    val isReminder: Boolean get() = kind == "reminder"
    /** Still to do: open, or a reminder snoozed to later (CAL-3). */
    val open: Boolean get() = status == "open" || status == "snoozed"
    /** CAL-3: a reminder that can be snoozed (not done or cancelled). */
    val snoozable: Boolean get() = kind == "reminder" && status in setOf("open", "snoozed", "fired", "missed")
    val at: Long? get() = startAt ?: dueAt
}

/** The dots a month cell shows: the kinds of the first items (at most the cap), and how many more there are. */
data class DayDots(val kinds: List<String>, val more: Int)

/** CAL-2: the pure rules of the Calendar tab (parsing, bucketing, dots, ordering). No Compose here, so it is tested on the JVM. */
object CalendarModel {
    /** Dots drawn in a month cell before "+n". */
    const val MAX_DOTS = 3
    /** The snooze step the item modal offers, in minutes. */
    /** CAL-3: the item id in a `calendar_open` frame body (`{"id":"cal-..."}`), or null. */
    fun openId(body: String?): String? = body?.let { runCatching { (kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject)?.get("id") }.getOrNull() }
        ?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }

    /** CAL-3: the snooze presets the daemon accepts, with their labels. */
    val SNOOZE_PRESETS = listOf("5m" to "Snooze 5 min", "1h" to "1 h", "tomorrow" to "Tomorrow")

    fun parseItem(e: JsonElement): CalItem? {
        val o = e as? JsonObject ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        fun l(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull
        val creator = o["createdBy"] as? JsonObject
        val by = creator?.let { c -> listOf("role", "agent", "kind").firstNotNullOfOrNull { (c[it] as? JsonPrimitive)?.contentOrNull } } ?: "user"
        return CalItem(
            id = s("id") ?: return null, kind = s("kind") ?: "task", title = s("title").orEmpty(), status = s("status") ?: "open", day = s("day") ?: return null,
            allDay = (o["allDay"] as? JsonPrimitive)?.booleanOrNull ?: true, zone = s("zone") ?: "UTC", notes = s("notes"),
            startAt = l("startAt"), dueAt = l("dueAt"), remindAt = l("remindAt"), target = s("target"), createdBy = by,
            history = (o["history"] as? JsonArray).orEmpty().mapNotNull { h ->
                (h as? JsonObject)?.let { ho -> CalHistory(l0(ho["at"]), (ho["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (ho["by"] as? JsonPrimitive)?.contentOrNull, (ho["detail"] as? JsonPrimitive)?.contentOrNull) }
            },
        )
    }

    private fun l0(e: JsonElement?) = (e as? JsonPrimitive)?.longOrNull ?: 0L

    fun parseItems(e: JsonElement?): List<CalItem> = (e as? JsonArray).orEmpty().mapNotNull(::parseItem)

    /**
     * Items by the day they were planned for. The day is the item's own (stored with its zone), never recomputed from the
     * viewer's zone: a 23:30 reminder in Tokyo stays on its Tokyo day wherever the window is. Within a day: all-day first, then by time.
     */
    fun byDay(items: List<CalItem>): Map<LocalDate, List<CalItem>> =
        items.mapNotNull { i -> runCatching { LocalDate.parse(i.day) }.getOrNull()?.let { it to i } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, l) -> l.sortedWith(compareBy<CalItem>({ !it.allDay }, { it.at ?: 0L }, { it.title })) }

    fun dots(items: List<CalItem>?): DayDots {
        val l = items.orEmpty()
        return DayDots(l.take(MAX_DOTS).map { it.kind + if (it.open) "" else ".closed" }, (l.size - MAX_DOTS).coerceAtLeast(0))
    }

    /** "HH:mm" of the item's time in its own zone, or "All day". */
    fun timeLabel(i: CalItem): String {
        val at = i.at
        if (i.allDay || at == null) return "All day"
        val t = Instant.fromEpochMilliseconds(at).toLocalDateTime(runCatching { TimeZone.of(i.zone) }.getOrDefault(TimeZone.UTC))
        return "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }
}

/** The create / edit form's fields as text; [problem] says what is wrong, [toArgs] is the `calendar/save` body. */
data class CalendarDraft(
    val id: String? = null, val kind: String = "task", val title: String = "", val notes: String = "", val date: String = "",
    val allDay: Boolean = true, val time: String = "09:00", val remindAt: String = "", val target: String = "",
) {
    fun problem(): String? = when {
        title.isBlank() -> "A title is needed."
        runCatching { LocalDate.parse(date.trim()) }.isFailure -> "The date must be yyyy-MM-dd."
        !allDay && !TIME.matches(time.trim()) -> "The time must be HH:mm."
        remindAt.isNotBlank() && !REMIND.matches(remindAt.trim()) -> "Remind at must be yyyy-MM-ddTHH:mm."
        target.isNotBlank() && kind != "reminder" -> "Only a reminder has a target."
        else -> null
    }

    fun toArgs(): String = buildJsonObject {
        id?.let { put("id", it) }
        put("kind", kind); put("title", title.trim()); put("notes", notes.trim()); put("date", date.trim()); put("allDay", allDay)
        if (!allDay) put("time", time.trim())
        remindAt.trim().takeIf { it.isNotEmpty() }?.let { put("remindAt", it) }
        // An edit sends a blank target to clear it; a create leaves it out (the user).
        if (id != null || target.isNotBlank()) put("target", target.trim())
    }.toString()

    companion object {
        private val TIME = Regex("""([01]\d|2[0-3]):[0-5]\d""")
        private val REMIND = Regex("""\d{4}-\d{2}-\d{2}T([01]\d|2[0-3]):[0-5]\d""")

        fun from(i: CalItem): CalendarDraft = CalendarDraft(
            id = i.id, kind = i.kind, title = i.title, notes = i.notes.orEmpty(), date = i.day, allDay = i.allDay,
            time = if (i.allDay) "09:00" else CalendarModel.timeLabel(i), target = i.target.orEmpty(),
        )
    }
}
