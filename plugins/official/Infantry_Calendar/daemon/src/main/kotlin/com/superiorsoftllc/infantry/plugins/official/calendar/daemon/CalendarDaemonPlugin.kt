// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * CAL-1 (Calendar epic): Infantry_Calendar's daemon half. Installs its tables (`storage.migrate`), serves the six agent tools
 * (open to every agent, `created_by` taken from the tool caller) and the `calendar_*` frames for the Calendar tab (CAL-2), and
 * broadcasts `calendar_changed` after every write. Firing reminders is CAL-3. The data stays when the plugin is disabled.
 */
class CalendarDaemonPlugin : DaemonPlugin {
    @Volatile private var service: CalendarService? = null

    override fun start(api: DaemonApi) {
        val store = CalendarStore(api.storage)
        store.install()
        val svc = CalendarService(
            store = store,
            limits = { CalendarLimits.read(api::config) },
            defaultZone = { zoneFrom(api.config(ZONE_KEY)) },
            sessionExists = { id -> runCatching { api.sessions.get(id) != null }.getOrDefault(true) },
            changed = { c -> runCatching { api.events.broadcast(CHANGED, CalendarJson.change(c).toString()) } },
        )
        service = svc
        val creators = CreatorOf { sessionId -> runCatching { api.sessions.get(sessionId)?.agentId }.getOrNull() }
        calendarTools(svc, creators).forEach(api.tools::register)
        registerFrames(api, svc)
    }

    override fun stop() { service = null }

    /** One operation over the service: (reply frame type, body). The frames and the HTTP routes the tab calls both run these. */
    private fun operations(svc: CalendarService): Map<String, Pair<String, (JsonObject) -> kotlinx.serialization.json.JsonElement>> {
        fun id(a: JsonObject) = a["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: throw CalendarRefusal("id is required")
        return mapOf(
            "list" to ("calendar_items" to { a: JsonObject ->
                fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                CalendarJson.items(svc.list(ItemFilter(s("from"), s("to"), s("status"), s("kind"), s("projectId"), s("createdBy"))))
            }),
            "get" to ("calendar_item" to { a: JsonObject ->
                val i = id(a)
                val item = CalendarJson.item(svc.get(i) ?: throw CalendarRefusal("no calendar item '$i'"))
                JsonObject(item + ("history" to kotlinx.serialization.json.buildJsonArray { svc.events(i).forEach { add(CalendarJson.event(it)) } }))
            }),
            "save" to ("calendar_item" to { a: JsonObject ->
                val input = CalendarJson.input(a)
                val existing = a["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                // The tab acts as the person: items it saves are the user's.
                CalendarJson.item(if (existing == null) svc.add(input, CalendarCreator.USER) else svc.update(existing, input, "user"))
            }),
            "complete" to ("calendar_item" to { a: JsonObject -> CalendarJson.item(svc.complete(id(a), a["status"]?.jsonPrimitive?.contentOrNull, "user")) }),
            "delete" to ("calendar_deleted" to { a: JsonObject -> val i = id(a); buildJsonObject { put("id", i); put("deleted", svc.delete(i)) } }),
            "clear" to ("calendar_deleted" to { _: JsonObject -> buildJsonObject { put("deleted", svc.clear()) } }),
        )
    }

    private fun registerFrames(api: DaemonApi, svc: CalendarService) {
        fun args(body: String): JsonObject = if (body.isBlank()) JsonObject(emptyMap()) else runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        for ((name, op) in operations(svc)) {
            val (replyType, run) = op
            api.frames.handle("calendar_$name") { f, _ ->
                try { Frame(replyType, run(args(f.data)).toString(), f.sessionId, f.requestId) }
                catch (e: CalendarRefusal) { Frame("error", e.message ?: "refused", f.sessionId, f.requestId) }
            }
            // CAL-2: the Calendar tab (a UI half) reaches the same operations over plugin HTTP routes: POST /plugins/<module>/calendar/<name>.
            api.routes.handle("POST", "calendar/$name") { call ->
                try { HttpReply(200, run(args(call.body)).toString(), "application/json") }
                catch (e: CalendarRefusal) { HttpReply(400, buildJsonObject { put("error", e.message ?: "refused") }.toString(), "application/json") }
            }
        }
    }

    private fun zoneFrom(name: String?): ZoneId =
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    companion object {
        /** The named push to clients after every write. */
        const val CHANGED = "calendar_changed"
        const val ZONE_KEY = "calendar.zone"
    }
}
