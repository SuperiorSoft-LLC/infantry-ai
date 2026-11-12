// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** NOTIFY-1: the frames of Infantry_Notify (the daemon half's `NotifyWire`; the two halves share no classes). */
object NotifyFrames {
    const val PUSH = "notify_push"
    const val ACK = "notify_ack"
    const val PENDING = "notify_pending"
    const val PENDING_LIST = "notify_pending_list"
    const val SOUNDS = "notify_sounds"
    const val SOUNDS_LIST = "notify_sounds_list"
    const val SOUND_GET = "notify_sound_get"
    const val SOUND = "notify_sound"

    /** NOTIFY-PUSH-1: [device] (`desktop`, `android`, `web`) makes the ack this device's own; the daemon keeps the notification for the others. */
    fun ackBody(id: String, device: String? = null): String = buildJsonObject { put("id", id); device?.let { put("device", it) } }.toString()

    /** NOTIFY-PUSH-1: the queue request names its device, so the daemon sends only what this device has not shown. */
    fun pending(device: String): String = buildJsonObject { put("device", device) }.toString()
    fun soundGet(name: String): String = buildJsonObject { put("name", name) }.toString()
}

/** One pushed notification, as the client reads it. [soundData] is base64 (null = silent). */
data class NotifyPayload(
    val id: String,
    val title: String,
    val body: String = "",
    val level: String = "info",
    val target: String = "all",
    val sound: String? = null,
    val soundType: String? = null,
    val soundData: String? = null,
    val ticketId: String? = null,
    val url: String? = null,
) {
    val urgent: Boolean get() = level == "urgent"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): NotifyPayload? = runCatching { of(json.parseToJsonElement(text)) }.getOrNull()

        fun parseList(text: String): List<NotifyPayload> =
            runCatching { (json.parseToJsonElement(text) as? JsonArray)?.mapNotNull(::of) }.getOrNull().orEmpty()

        fun of(e: JsonElement): NotifyPayload? {
            val o = e as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            return NotifyPayload(
                id = s("id") ?: return null, title = s("title") ?: return null, body = s("body").orEmpty(),
                level = s("level") ?: "info", target = s("target") ?: "all",
                sound = s("sound"), soundType = s("soundType"), soundData = s("soundData"),
                ticketId = s("ticketId"), url = s("url"),
            )
        }
    }
}

/** One row of the sound list (the settings card). */
data class SoundRow(val name: String, val builtin: Boolean, val bytes: Long)

object NotifyLists {
    private val json = Json { ignoreUnknownKeys = true }

    fun sounds(text: String): List<SoundRow> = runCatching {
        (json.parseToJsonElement(text) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SoundRow(name, (o["builtin"] as? JsonPrimitive)?.booleanOrNull ?: false, (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0)
        }
    }.getOrNull().orEmpty()

    /** The `notify_sound` answer: (name, type, base64) or null when the daemon had no such sound. */
    fun sound(text: String): Triple<String, String, String>? = runCatching {
        val o = json.parseToJsonElement(text) as JsonObject
        val data = (o["data"] as? JsonPrimitive)?.contentOrNull ?: return null
        Triple((o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(), (o["type"] as? JsonPrimitive)?.contentOrNull ?: "audio/wav", data)
    }.getOrNull()
}

/** Which client shows what: the target filter, and the Android channel of a level. Pure, so the mapping is unit tested. */
object NotifyRouting {
    const val CHANNEL_MILESTONES = "infantry.milestones"
    const val CHANNEL_URGENT = "infantry.urgent"

    /** [target] `all` reaches every client; otherwise only the client whose platform is [target]. */
    fun shows(target: String, platform: String): Boolean = target == "all" || target.equals(platform, ignoreCase = true)

    /** Android: `urgent` posts on the high-importance channel (heads-up), info and milestone on "Milestones". */
    fun androidChannel(level: String): String = if (level == "urgent") CHANNEL_URGENT else CHANNEL_MILESTONES

    /** Android: a stable notification id per payload id, so a replay of the same notification replaces it. */
    fun androidId(id: String): Int = id.hashCode() and 0x7fffffff
}

/** The ids this client already showed (a replay of the queue never shows one twice). Bounded. */
class NotifyDedupe(private val max: Int = 500) {
    private val seen = LinkedHashSet<String>()

    /** True when [id] is new (and remembers it). */
    fun firstTime(id: String): Boolean {
        if (!seen.add(id)) return false
        while (seen.size > max) seen.remove(seen.first())
        return true
    }
}
