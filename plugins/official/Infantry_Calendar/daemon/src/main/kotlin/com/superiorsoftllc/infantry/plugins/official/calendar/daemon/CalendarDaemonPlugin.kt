// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.calendar.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.Frame
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

    private fun registerFrames(api: DaemonApi, svc: CalendarService) {
        fun reply(f: Frame, type: String, body: () -> kotlinx.serialization.json.JsonElement): Frame =
            try { Frame(type, body().toString(), f.sessionId, f.requestId) }
            catch (e: CalendarRefusal) { Frame("error", e.message ?: "refused", f.sessionId, f.requestId) }
        fun args(f: Frame): JsonObject = if (f.data.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(f.data) as? JsonObject ?: JsonObject(emptyMap())
        fun id(a: JsonObject) = a["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: throw CalendarRefusal("id is required")
        // The tab acts as the person: items it saves are the user's.
        api.frames.handle("calendar_list") { f, _ ->
            reply(f, "calendar_items") {
                val a = args(f)
                fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                CalendarJson.items(svc.list(ItemFilter(s("from"), s("to"), s("status"), s("kind"), s("projectId"), s("createdBy"))))
            }
        }
        api.frames.handle("calendar_get") { f, _ ->
            reply(f, "calendar_item") {
                val i = id(args(f))
                CalendarJson.item(svc.get(i) ?: throw CalendarRefusal("no calendar item '$i'"))
            }
        }
        api.frames.handle("calendar_save") { f, _ ->
            reply(f, "calendar_item") {
                val a = args(f)
                val input = CalendarJson.input(a)
                val existing = a["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                CalendarJson.item(if (existing == null) svc.add(input, CalendarCreator.USER) else svc.update(existing, input, "user"))
            }
        }
        api.frames.handle("calendar_complete") { f, _ ->
            val a = args(f)
            reply(f, "calendar_item") { CalendarJson.item(svc.complete(id(a), a["status"]?.jsonPrimitive?.contentOrNull, "user")) }
        }
        api.frames.handle("calendar_delete") { f, _ ->
            reply(f, "calendar_deleted") { val i = id(args(f)); buildJsonObject { put("id", i); put("deleted", svc.delete(i)) } }
        }
        api.frames.handle("calendar_clear") { f, _ ->
            reply(f, "calendar_deleted") { buildJsonObject { put("deleted", svc.clear()) } }
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
