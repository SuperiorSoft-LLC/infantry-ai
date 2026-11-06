// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify.daemon

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.LocalTime

/** NOTIFY-1: the wire names of the plugin. The daemon pushes [PUSH] to clients; a client that showed one answers [ACK]. */
object NotifyWire {
    const val PUSH = "notify_push"
    const val ACK = "notify_ack"
    /** Client asks for the queue; answered by [PENDING_LIST] (a JSON array of push payloads). */
    const val PENDING = "notify_pending"
    const val PENDING_LIST = "notify_pending_list"
    /** Client asks for the sound list (settings card); answered by [SOUNDS_LIST]. */
    const val SOUNDS = "notify_sounds"
    const val SOUNDS_LIST = "notify_sounds_list"
    /** Client asks for one sound `{name}` (the Play button); answered by [SOUND] `{name, type, data}` (no data = unknown). */
    const val SOUND_GET = "notify_sound_get"
    const val SOUND = "notify_sound"
}

/** The three levels. Only [URGENT] rings during quiet hours. */
object NotifyLevel {
    const val INFO = "info"
    const val MILESTONE = "milestone"
    const val URGENT = "urgent"
    val ALL = listOf(INFO, MILESTONE, URGENT)
}

/** Which of the owner's clients show a notification. */
object NotifyTarget {
    const val ALL = "all"
    val KNOWN = listOf(ALL, "desktop", "android", "web")
}

/** The arguments of one `infantry_notify` call, validated. [sound] null = the setting `notify.defaultSound`; `none` = silent. */
data class NotifyRequest(
    val title: String,
    val body: String = "",
    val level: String = NotifyLevel.INFO,
    val sound: String? = null,
    val target: String = NotifyTarget.ALL,
    val ticketId: String? = null,
    val url: String? = null,
) {
    companion object {
        const val MAX_TITLE = 200
        const val MAX_BODY = 2000

        /** Parses tool arguments; the error text says which argument is wrong. */
        fun parse(args: JsonObject): Result<NotifyRequest> {
            fun str(key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val title = str("title") ?: return Result.failure(IllegalArgumentException("title is required"))
            if (title.length > MAX_TITLE) return Result.failure(IllegalArgumentException("title is longer than $MAX_TITLE characters"))
            val body = str("body").orEmpty()
            if (body.length > MAX_BODY) return Result.failure(IllegalArgumentException("body is longer than $MAX_BODY characters"))
            val level = (str("level") ?: NotifyLevel.INFO).lowercase()
            if (level !in NotifyLevel.ALL) return Result.failure(IllegalArgumentException("level must be one of ${NotifyLevel.ALL.joinToString(", ")}"))
            val target = (str("target") ?: NotifyTarget.ALL).lowercase()
            if (target !in NotifyTarget.KNOWN) return Result.failure(IllegalArgumentException("target must be one of ${NotifyTarget.KNOWN.joinToString(", ")}"))
            val sound = str("sound")
            if (sound != null && !NotifySounds.validName(sound)) return Result.failure(IllegalArgumentException("sound must be a sound id or a plain file name (no folders): $sound"))
            val url = str("url")
            if (url != null && !(url.startsWith("https://") || url.startsWith("http://"))) return Result.failure(IllegalArgumentException("url must start with http:// or https://"))
            return Result.success(NotifyRequest(title, body, level, sound, target, str("ticketId"), url))
        }
    }
}

/** One notification as it is queued and pushed. [soundData] is the sound file in base64 (null = silent). */
data class NotifyMessage(
    val id: String,
    val title: String,
    val body: String,
    val level: String,
    val target: String,
    val at: Long,
    val sound: String? = null,
    val soundType: String? = null,
    val soundData: String? = null,
    val ticketId: String? = null,
    val url: String? = null,
    val projectId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("title", title); put("body", body); put("level", level); put("target", target); put("at", at)
        sound?.let { put("sound", it) }; soundType?.let { put("soundType", it) }; soundData?.let { put("soundData", it) }
        ticketId?.let { put("ticketId", it) }; url?.let { put("url", it) }; projectId?.let { put("projectId", it) }
    }

    companion object {
        fun fromJson(e: JsonElement): NotifyMessage? {
            val o = e as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            return NotifyMessage(
                id = s("id") ?: return null, title = s("title") ?: return null, body = s("body").orEmpty(),
                level = s("level") ?: NotifyLevel.INFO, target = s("target") ?: NotifyTarget.ALL,
                at = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                sound = s("sound"), soundType = s("soundType"), soundData = s("soundData"),
                ticketId = s("ticketId"), url = s("url"), projectId = s("projectId"),
            )
        }
    }
}

/**
 * The plugin's settings (module.json `daemon.config`, all under `notify.*`). Every default is the quietest choice that still
 * lets an explicit request through: nothing in this plugin fires on its own, so the master switch defaults on, the sounds
 * default to `none` and quiet hours default off.
 */
data class NotifySettings(
    val enabled: Boolean = true,
    /** `HH:mm-HH:mm` in the daemon's local time; empty = off. Inside it only `urgent` plays a sound. */
    val quietHours: String = "",
    val defaultSound: String = NotifySounds.NONE,
    val milestoneSound: String = NotifySounds.NONE,
    /** How long an undelivered notification waits for a client, in minutes. */
    val queueMaxAgeMinutes: Long = 1440,
    /** How many undelivered notifications are kept (oldest dropped first). */
    val queueMax: Int = 50,
    /** The largest sound file sent with a notification, in bytes; a larger one is sent silent. */
    val maxSoundBytes: Long = 524_288,
) {
    fun inQuietHours(now: LocalTime): Boolean {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$""").find(quietHours) ?: return false
        val (h1, m1, h2, m2) = m.destructured
        val from = runCatching { LocalTime.of(h1.toInt(), m1.toInt()) }.getOrNull() ?: return false
        val to = runCatching { LocalTime.of(h2.toInt(), m2.toInt()) }.getOrNull() ?: return false
        return if (from <= to) now >= from && now < to else now >= from || now < to
    }

    companion object {
        fun from(config: (String) -> String?): NotifySettings {
            val d = NotifySettings()
            fun bool(k: String, def: Boolean) = config(k)?.trim()?.let { JsonPrimitive(it).booleanOrNull ?: it.equals("true", true) } ?: def
            fun long(k: String, def: Long, min: Long, max: Long) = config(k)?.trim()?.toDoubleOrNull()?.toLong()?.coerceIn(min, max) ?: def
            return NotifySettings(
                enabled = bool("notify.enabled", d.enabled),
                quietHours = config("notify.quietHours")?.trim().orEmpty(),
                defaultSound = config("notify.defaultSound")?.trim()?.takeIf { it.isNotEmpty() } ?: d.defaultSound,
                milestoneSound = config("notify.milestoneSound")?.trim()?.takeIf { it.isNotEmpty() } ?: d.milestoneSound,
                queueMaxAgeMinutes = long("notify.queueMaxAgeMinutes", d.queueMaxAgeMinutes, 0, 525_600),
                queueMax = long("notify.queueMax", d.queueMax.toLong(), 0, 10_000).toInt(),
                maxSoundBytes = long("notify.maxSoundBytes", d.maxSoundBytes, 0, 16_777_216),
            )
        }
    }
}
