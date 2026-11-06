// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ConnectedHook
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.HttpReply
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * NOTIFY-1 (ticket cd8639ff): Infantry_Notify's daemon half. System notifications for milestones only: nothing here fires on
 * its own. A notification is sent when a script (`ih.infantry_notify`), an agent (`infantry_notify`), the CLI
 * (`infantry notify --title ...`) asks, or when a ticket marked with `infantry_notify_milestone` enters done.
 *  - Push: a `notify_push` frame to every client; the frame stays queued until a client answers `notify_ack`, and every
 *    client that connects receives the queue (the `connected` hook), so one sent with no client connected arrives later.
 *  - Routes: `GET /plugins/Infantry_Notify/sounds` (the list) and `GET /plugins/Infantry_Notify/sound?name=` (one, base64),
 *    for the settings card's Play button.
 */
class NotifyDaemonPlugin : DaemonPlugin {
    private val closers = mutableListOf<AutoCloseable>()

    override fun start(api: DaemonApi) {
        val service = NotifyService(
            api.stateDir, { NotifySettings.from(api::config) },
            push = { api.events.broadcast(NotifyWire.PUSH, it.toJson().toString()) },
            log = api::log,
        )
        api.tools.register(NotifyTool(service))
        api.tools.register(MilestoneTool(service))
        api.frames.handle(NotifyWire.ACK) { frame: Frame, _ ->
            val id = runCatching { (Json.parseToJsonElement(frame.data) as? JsonObject)?.get("id")?.let { (it as JsonPrimitive).contentOrNull } }.getOrNull()
            if (id != null) service.ack(id)
            null
        }
        api.hooks.connected(ConnectedHook { service.pending().map { Frame(NotifyWire.PUSH, it.toJson().toString()) } })
        // A client that starts listening after its connect asks for the queue itself (the answer is a list of pushes).
        api.frames.handle(NotifyWire.PENDING) { frame: Frame, _ ->
            Frame(NotifyWire.PENDING_LIST, JsonArray(service.pending().map { it.toJson() }).toString(), frame.sessionId, frame.requestId)
        }
        api.frames.handle(NotifyWire.SOUNDS) { frame: Frame, _ ->
            Frame(NotifyWire.SOUNDS_LIST, soundsJson(service).toString(), frame.sessionId, frame.requestId)
        }
        api.frames.handle(NotifyWire.SOUND_GET) { frame: Frame, _ ->
            val name = runCatching { ((Json.parseToJsonElement(frame.data) as JsonObject)["name"] as JsonPrimitive).contentOrNull }.getOrNull()
            val s = runCatching { service.sounds.resolve(name) }.getOrNull()
            Frame(NotifyWire.SOUND, buildJsonObject {
                put("name", name.orEmpty())
                if (s != null) { put("type", s.type); put("data", Base64.getEncoder().encodeToString(s.bytes)) }
            }.toString(), frame.sessionId, frame.requestId)
        }
        api.routes.handle("GET", "sounds") {
            HttpReply(200, soundsJson(service).toString(), "application/json")
        }
        api.routes.handle("GET", "sound") { call ->
            try {
                val s = service.sounds.resolve(call.query["name"]) ?: return@handle HttpReply(404, "silent")
                HttpReply(200, buildJsonObject { put("name", s.name); put("type", s.type); put("data", Base64.getEncoder().encodeToString(s.bytes)) }.toString(), "application/json")
            } catch (e: IllegalArgumentException) {
                HttpReply(404, e.message ?: "unknown sound")
            }
        }
        closers += api.events.onTicket("Infantry_Notify") { e ->
            if (e.kind != TicketEvent.DONE && !(e.isTransition && e.to == "done")) return@onTicket
            if (service.milestones().none { it.ticketId == e.ticketId }) return@onTicket
            val info = (api.tickets.get(e.ticketId, Caller(owner = true)) as? BoardResult.Ok)?.value
            service.onTicketDone(e.ticketId, e.seq, info?.title, info?.projectId)
        }
    }

    override fun stop() {
        closers.forEach { runCatching { it.close() } }
        closers.clear()
    }
}

internal fun soundsJson(service: NotifyService): JsonArray = buildJsonArray {
    service.sounds.list().forEach { s -> add(buildJsonObject { put("name", s.name); put("builtin", s.builtin); put("bytes", s.size) }) }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.prop(name: String, type: String, description: String) =
    put(name, buildJsonObject { put("type", type); put("description", description) })

/** `infantry_notify`: one system notification on the owner's devices. */
class NotifyTool(private val service: NotifyService) : DaemonTool {
    override val name = "infantry_notify"
    override val description =
        "Send ONE system notification (Windows toast, Android notification, browser notification) to the owner's devices, for " +
            "milestones and checkpoints only: never for routine progress. Queued and delivered on the next connect when no client is " +
            "connected. level urgent rings even in quiet hours (use it to wake the owner). sound: a built-in id (chime, bell, alarm), " +
            "a file name in plugin-data/Infantry_Notify/sounds, or none."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            prop("title", "string", "The headline (required, at most 200 characters)")
            prop("body", "string", "The text under it (optional)")
            prop("level", "string", "info (default) | milestone | urgent")
            prop("sound", "string", "A sound id or file name; none = silent; omitted = the setting notify.defaultSound (notify.milestoneSound for level milestone)")
            prop("target", "string", "all (default) | desktop | android | web")
            prop("ticketId", "string", "A ticket to open when the notification is clicked")
            prop("url", "string", "An http(s) link to open when the notification is clicked")
        })
        put("required", buildJsonArray { add(JsonPrimitive("title")) })
        put("additionalProperties", false)
    }
    override val annotations = buildJsonObject {
        put("readOnlyHint", false); put("destructiveHint", false); put("idempotentHint", false); put("openWorldHint", false)
    }

    override fun call(call: ToolCall): ToolResult {
        val req = NotifyRequest.parse(call.arguments).getOrElse { return ToolResult(it.message ?: "bad arguments", isError = true) }
        return try {
            when (val o = service.notify(req, call.caller.project)) {
                is NotifyService.Outcome.Off -> ToolResult("not sent: ${o.reason}")
                is NotifyService.Outcome.Sent -> ToolResult(
                    "notified (${o.message.level}, id ${o.message.id}, sound ${o.message.sound ?: "none"}" +
                        (o.silentReason?.let { ", silent: $it" } ?: "") + "): ${o.message.title}",
                )
            }
        } catch (e: IllegalArgumentException) {
            ToolResult(e.message ?: "bad arguments", isError = true)
        }
    }
}

/** `infantry_notify_milestone`: mark a ticket so a milestone notification fires when it enters done (the notifyOnDone flag). */
class MilestoneTool(private val service: NotifyService) : DaemonTool {
    override val name = "infantry_notify_milestone"
    override val description =
        "Mark a ticket as a milestone (notifyOnDone): when it enters done, ONE milestone notification is sent to the owner's devices. " +
            "on=false clears the mark. Without ticketId, lists the marked tickets."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            prop("ticketId", "string", "The ticket (full id); omitted = list the marked tickets")
            prop("on", "boolean", "true (default) marks the ticket, false clears the mark")
            prop("sound", "string", "The sound of its notification (a sound id or file name, none = silent); omitted = notify.milestoneSound")
        })
        put("additionalProperties", false)
    }

    override fun call(call: ToolCall): ToolResult {
        val a = call.arguments
        val ticketId = (a["ticketId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult(service.milestones().let { l ->
                if (l.isEmpty()) "no milestone tickets" else l.joinToString("\n") { "${it.ticketId} sound ${it.sound ?: "(default)"}" }
            })
        val on = (a["on"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.equals("true", true) } ?: true
        val sound = (a["sound"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            val m = service.setMilestone(ticketId, on, sound)
            ToolResult(if (m == null) "$ticketId: milestone mark cleared" else "$ticketId: notifies on done (sound ${m.sound ?: "notify.milestoneSound"})")
        } catch (e: IllegalArgumentException) {
            ToolResult(e.message ?: "bad arguments", isError = true)
        }
    }
}
