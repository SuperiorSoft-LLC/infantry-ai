// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar

import com.superiorsoftllc.infantry.plugin.api.DaemonAccess
import com.superiorsoftllc.infantry.plugin.api.DaemonReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val BASE = "POST /plugins/Infantry_Calendar/calendar/"

/** The routes module.json declares (the tab may call only these). */
val CALENDAR_ROUTES = listOf("list", "get", "save", "complete", "snooze", "delete", "clear").map { BASE + it }

/** The event the host republishes from the daemon's `calendar_changed` frame (module.json `daemonFrames`). */
const val CALENDAR_CHANGED_EVENT = "daemon.calendar_changed"

/** CAL-3: the event the host republishes from the daemon's `calendar_open` frame (a reminder notification's Open button); data `id`. */
const val CALENDAR_OPEN_EVENT = "daemon.calendar_open"

/** CAL-2: the Calendar tab's requests, over the plugin HTTP routes of the daemon half. Every call answers a Result carrying the daemon's refusal text. */
class CalendarApi(private val daemon: DaemonAccess?) {
    private suspend fun call(op: String, body: String): Result<JsonElement> {
        val d = daemon ?: return Result.failure(IllegalStateException("The daemon is not available."))
        return when (val r = d.call(BASE + op, body)) {
            is DaemonReply.Ok -> {
                val json = runCatching { Json.parseToJsonElement(r.body) }.getOrNull()
                if (r.status in 200..299 && json != null) Result.success(json)
                else Result.failure(IllegalStateException(((json as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull ?: "The daemon answered ${r.status}."))
            }
            is DaemonReply.Refused -> Result.failure(IllegalStateException(r.reason))
            is DaemonReply.Failed -> Result.failure(IllegalStateException(r.reason))
        }
    }

    suspend fun list(from: String, to: String): Result<List<CalItem>> =
        call("list", """{"from":"$from","to":"$to"}""").map { CalendarModel.parseItems(it) }

    suspend fun get(id: String): Result<CalItem?> = call("get", """{"id":"$id"}""").map { CalendarModel.parseItem(it) }
    suspend fun save(argsJson: String): Result<CalItem?> = call("save", argsJson).map { CalendarModel.parseItem(it) }
    suspend fun complete(id: String, status: String): Result<CalItem?> = call("complete", """{"id":"$id","status":"$status"}""").map { CalendarModel.parseItem(it) }
    /** CAL-3: ring again later; [preset] is `5m`, `1h` or `tomorrow`. */
    suspend fun snooze(id: String, preset: String): Result<CalItem?> = call("snooze", """{"id":"$id","preset":"$preset"}""").map { CalendarModel.parseItem(it) }
    /** CAL-4: the explicit wipe of every item, its history and the calendar's owned triggers. */
    suspend fun clear(): Result<Unit> = call("clear", "{}").map { }
    suspend fun delete(id: String): Result<Unit> = call("delete", """{"id":"$id"}""").map { }
}
