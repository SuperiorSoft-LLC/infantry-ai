// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The JSON a tool or frame answers with for an item. */
object CalendarJson {
    fun item(i: CalendarItem): JsonObject = buildJsonObject {
        put("id", i.id); put("kind", i.kind); put("title", i.title); put("status", i.status); put("day", i.day); put("allDay", i.allDay)
        put("zone", i.zone)
        i.notes?.let { put("notes", it) }
        i.projectId?.let { put("projectId", it) }
        i.startAt?.let { put("startAt", it) }
        i.dueAt?.let { put("dueAt", it) }
        i.remindAt?.let { put("remindAt", it) }
        i.target?.let { put("target", it) }
        i.firedAt?.let { put("firedAt", it) }
        put("createdBy", buildJsonObject {
            put("kind", i.creator.kind)
            i.creator.agent?.let { put("agent", it) }; i.creator.session?.let { put("session", it) }; i.creator.role?.let { put("role", it) }
        })
        put("createdAt", i.createdAt); put("updatedAt", i.updatedAt)
    }

    fun items(l: List<CalendarItem>): JsonArray = buildJsonArray { l.forEach { add(item(it)) } }

    fun event(e: CalendarEvent): JsonObject = buildJsonObject {
        put("at", e.at); put("kind", e.kind); e.by?.let { put("by", it) }; e.detail?.let { put("detail", it) }
    }

    fun change(c: CalendarChange): JsonObject = buildJsonObject {
        put("op", c.op)
        put("ids", buildJsonArray { c.ids.forEach { add(JsonPrimitive(it)) } })
        put("days", buildJsonArray { c.days.forEach { add(JsonPrimitive(it)) } })
    }

    /** An [ItemInput] from tool / frame arguments (strings are trimmed; numbers may arrive as strings). */
    fun input(a: JsonObject): ItemInput {
        fun str(k: String) = (a[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        fun lng(k: String) = (a[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }
        fun bool(k: String) = (a[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.toBooleanStrictOrNull() }
        return ItemInput(
            kind = str("kind"), title = str("title"), notes = str("notes"), date = str("date"), time = str("time"), inMinutes = lng("inMinutes"),
            allDay = bool("allDay"), remindAt = str("remindAt"), remindBefore = lng("remindBefore"), target = str("target"),
            projectId = str("projectId"), zone = str("zone"),
        )
    }
}

/** Who made a call: an agent session (its id, agent id and role as the call reported them), else the person at the CLI. */
class CreatorOf(private val agentOf: (sessionId: String) -> String?) {
    fun of(call: ToolCall): CalendarCreator {
        val session = call.clientSessionId?.trim()?.takeIf { it.isNotEmpty() }
        val role = call.clientRole?.trim()?.takeIf { it.isNotEmpty() }
        if (session == null && !call.caller.agent) return CalendarCreator.USER
        return CalendarCreator("agent", agent = session?.let(agentOf), session = session, role = role)
    }

    fun label(c: CalendarCreator) = c.agent ?: c.role ?: c.kind
}

private fun prop(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }

private val ITEM_PROPS = listOf(
    "title" to prop("string", "What it is."),
    "notes" to prop("string", "Markdown details."),
    "date" to prop("string", "Day, yyyy-MM-dd (default today)."),
    "time" to prop("string", "Time of day, HH:mm, in the item's zone. Without it the item is all day."),
    "inMinutes" to prop("integer", "Minutes from now (instead of date and time)."),
    "allDay" to prop("boolean", "All day on date."),
    "remindAt" to prop("string", "When the alarm rings, yyyy-MM-ddTHH:mm (or epoch ms). Default: the item's time."),
    "remindBefore" to prop("integer", "Minutes before the item's time to ring."),
    "target" to prop("string", "Who is reminded: role:<role> or session:<id>. Omit to remind the user."),
    "projectId" to prop("string", "Show the item under this project's filter."),
    "zone" to prop("string", "IANA time zone of date and time (default: the user's)."),
)

private fun schema(required: List<String>, vararg props: Pair<String, JsonObject>) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    put("additionalProperties", false)
}

private val readOnly = buildJsonObject { put("readOnlyHint", true) }

private fun answer(block: () -> JsonElement): ToolResult = try {
    ToolResult(block().toString())
} catch (e: CalendarRefusal) {
    ToolResult(e.message ?: "refused", isError = true)
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

/** `infantry_calendar_add`: a task or reminder; created_by is the calling agent / session / role. */
class CalendarAddTool(private val service: CalendarService, private val creators: CreatorOf) : DaemonTool {
    override val name = "infantry_calendar_add"
    override val description = "Add a task or a reminder to the user's calendar. Give date (yyyy-MM-dd) + time (HH:mm), or inMinutes, or allDay. A reminder rings at remindAt / remindBefore / its time; target role:<role> or session:<id> reminds that agent, omitted reminds the user."
    override val inputSchema = schema(listOf("title"), "kind" to prop("string", "task (default) or reminder."), *ITEM_PROPS.toTypedArray())
    override fun call(call: ToolCall) = answer { CalendarJson.item(service.add(CalendarJson.input(call.arguments), creators.of(call))) }
}

/** `infantry_calendar_timer`: a reminder `minutes` from now. */
class CalendarTimerTool(private val service: CalendarService, private val creators: CreatorOf) : DaemonTool {
    override val name = "infantry_calendar_timer"
    override val description = "A reminder `minutes` from now: title, minutes, optional target (role:<role> or session:<id>; omitted = the user)."
    override val inputSchema = schema(listOf("title", "minutes"),
        "title" to prop("string", "What to be reminded of."), "minutes" to prop("integer", "Minutes from now."),
        "target" to prop("string", "role:<role> or session:<id>; omit to remind the user."), "projectId" to prop("string", "Project filter."))
    override fun call(call: ToolCall) = answer {
        val a = call.arguments
        val minutes = (a["minutes"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull() }
            ?: throw CalendarRefusal("minutes is required")
        val input = CalendarJson.input(a).copy(kind = "reminder", inMinutes = minutes)
        CalendarJson.item(service.add(input, creators.of(call)))
    }
}

class CalendarListTool(private val service: CalendarService) : DaemonTool {
    override val name = "infantry_calendar_list"
    override val description = "List calendar items, oldest day first. Optional from / to (yyyy-MM-dd, inclusive), status, kind, projectId, createdBy (an agent id, or user)."
    override val inputSchema = schema(emptyList(),
        "from" to prop("string", "First day, yyyy-MM-dd."), "to" to prop("string", "Last day, yyyy-MM-dd."),
        "status" to prop("string", "open | done | cancelled | fired | missed | snoozed."), "kind" to prop("string", "task | reminder."),
        "projectId" to prop("string", "Only this project."), "createdBy" to prop("string", "An agent id, or user."))
    override val annotations = readOnly
    override fun call(call: ToolCall) = answer {
        val a = call.arguments
        CalendarJson.items(service.list(ItemFilter(a.str("from"), a.str("to"), a.str("status"), a.str("kind"), a.str("projectId"), a.str("createdBy"))))
    }
}

class CalendarGetTool(private val service: CalendarService) : DaemonTool {
    override val name = "infantry_calendar_get"
    override val description = "One calendar item by id, with its history."
    override val inputSchema = schema(listOf("id"), "id" to prop("string", "The item id (cal-xxxxxxxx)."))
    override val annotations = readOnly
    override fun call(call: ToolCall) = answer {
        val id = call.arguments.str("id") ?: throw CalendarRefusal("id is required")
        val item = service.get(id) ?: throw CalendarRefusal("no calendar item '$id'")
        buildJsonObject {
            put("item", CalendarJson.item(item))
            put("history", buildJsonArray { service.events(id).forEach { add(CalendarJson.event(it)) } })
        }
    }
}

class CalendarUpdateTool(private val service: CalendarService, private val creators: CreatorOf) : DaemonTool {
    override val name = "infantry_calendar_update"
    override val description = "Change a calendar item: id plus any of title, notes, date, time, allDay, remindAt, remindBefore, target (empty text clears it), projectId."
    override val inputSchema = schema(listOf("id"), "id" to prop("string", "The item id."), *ITEM_PROPS.toTypedArray())
    override fun call(call: ToolCall) = answer {
        val id = call.arguments.str("id") ?: throw CalendarRefusal("id is required")
        CalendarJson.item(service.update(id, CalendarJson.input(call.arguments), creators.label(creators.of(call))))
    }
}

class CalendarCompleteTool(private val service: CalendarService, private val creators: CreatorOf) : DaemonTool {
    override val name = "infantry_calendar_complete"
    override val description = "Mark a calendar item done (default) or cancelled: id, optional status done | cancelled | open."
    override val inputSchema = schema(listOf("id"), "id" to prop("string", "The item id."), "status" to prop("string", "done (default), cancelled or open."))
    override fun call(call: ToolCall) = answer {
        val id = call.arguments.str("id") ?: throw CalendarRefusal("id is required")
        CalendarJson.item(service.complete(id, call.arguments.str("status"), creators.label(creators.of(call))))
    }
}

/** CAL-4: `infantry_calendar_clear`, the explicit wipe: every item, its history and the calendar's owned triggers. `confirm` must be true. */
class CalendarClearTool(private val service: CalendarService) : DaemonTool {
    override val name = "infantry_calendar_clear"
    override val description = "Delete ALL calendar data: every item, its history and the triggers the calendar owns. Irreversible. Only when the user asked for it; confirm must be true."
    override val inputSchema = schema(listOf("confirm"), "confirm" to prop("boolean", "Must be true."))
    override fun call(call: ToolCall) = answer {
        val ok = (call.arguments["confirm"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.trim()?.toBooleanStrictOrNull() } == true
        if (!ok) throw CalendarRefusal("confirm must be true to delete all calendar data")
        buildJsonObject { put("deleted", service.clear()) }
    }
}

fun calendarTools(service: CalendarService, creators: CreatorOf): List<DaemonTool> = listOf(
    CalendarAddTool(service, creators), CalendarTimerTool(service, creators), CalendarListTool(service), CalendarGetTool(service),
    CalendarUpdateTool(service, creators), CalendarCompleteTool(service, creators), CalendarClearTool(service),
)
