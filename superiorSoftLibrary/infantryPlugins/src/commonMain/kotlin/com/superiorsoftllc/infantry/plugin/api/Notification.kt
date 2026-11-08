// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * NOTIFY-CORE-1 (docs/wiki/Notifications.md, @since plugin API 2.1.0, revision 6): the ONE way to tell the owner something.
 * Every feature and plugin posts a [Notification] to the bus ([Notifications.notify]: the UI service, `DaemonApi.notifications`,
 * Lua `ih.call("notify", ...)`); the bus routes it to channels ([NotificationChannels]) by [NotificationSettings] and each
 * channel's one active [NotificationPresenter] shows it. A plugin draws no toast, snackbar or dialog of its own.
 */
@Serializable
enum class NotificationLevel {
    @SerialName("info") INFO,
    @SerialName("success") SUCCESS,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
    @SerialName("urgent") URGENT;

    val wire: String get() = name.lowercase()

    companion object {
        /** [text] as a level: the wire names plus `warn` and `milestone` (= success); anything else is [INFO]. */
        fun of(text: String?): NotificationLevel {
            val t = text?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == t } ?: when (t) {
                "warn" -> WARNING
                "milestone" -> SUCCESS
                else -> INFO
            }
        }
    }
}

/** One button on a notification. Pressing it reaches every [Notifications.onAction] listener as a [NotificationActionEvent]. */
@Serializable
data class NotificationAction(val id: String, val label: String)

/**
 * One notification.
 *
 * - [id]: blank = the bus assigns one. A second post of an id already in the history is dropped.
 * - [source]: who posted it (a module name, `daemon`, `script:<name>`); set by the host when blank.
 * - [sticky]: stays until dismissed; else it leaves after [timeoutMs] (0 = the presenter's default).
 * - [dedupeKey]: posts with the same key within `notifications.dedupeWindowSec` of the last one show once (one toast per outage).
 * - [channels]: empty = the routing rules for [level]; else exactly these channels (quiet hours still apply).
 * - [ticketId] / [sessionId] / [projectId]: what clicking it opens.
 * - [at]: epoch millis; 0 = the bus stamps it.
 */
@Serializable
data class Notification(
    val title: String,
    val body: String = "",
    val level: NotificationLevel = NotificationLevel.INFO,
    val id: String = "",
    val source: String = "",
    val actions: List<NotificationAction> = emptyList(),
    val sticky: Boolean = false,
    val timeoutMs: Long = 0,
    val dedupeKey: String? = null,
    val channels: List<String> = emptyList(),
    val ticketId: String? = null,
    val sessionId: String? = null,
    val projectId: String? = null,
    val at: Long = 0,
)

/** The built-in channel names. A channel is a plain string, so a plugin may present on one of its own (route it in the settings). */
object NotificationChannels {
    /** The in-app toast (and the history). The core default presenter always exists here. */
    const val IN_APP = "inApp"

    /** The operating system's notification (Windows toast, Android, browser). Core has no default: a plugin provides it. */
    const val OS = "os"

    /** The presenter setting value that forces the core default presenter of a channel. */
    const val CORE = "core"

    val BUILT_IN: List<String> = listOf(IN_APP, OS)
}

/**
 * Shows notifications on one or more channels. Register with [Notifications.present]; exactly one presenter is active per
 * channel (`notifications.presenter.<channel>`). [show] answers false when it cannot show this one (no tray, permission
 * refused); the bus then falls back to the core default. A presenter that throws is contained: it is skipped from then on
 * and the core default shows the notification.
 */
fun interface NotificationPresenter {
    fun show(notification: Notification): Boolean

    /** Takes notification [id] down, if this presenter still shows it. */
    fun dismiss(id: String) {}
}

/** A pressed [NotificationAction]: [actionId] of notification [notificationId], posted by [source]. */
@Serializable
data class NotificationActionEvent(val notificationId: String, val actionId: String, val source: String = "")

/**
 * The bus's settings (core keys, app scope). Routes and presenters are per channel; the defaults are the cheapest that still
 * reach the owner: everything in-app, only [NotificationLevel.URGENT] on the OS too.
 *
 * - `notifications.route.<level>`: comma-separated channels (`inApp,os`); `none` or "" = history only.
 * - `notifications.presenter.<channel>` (`inApp`, `os`): "" = the last plugin presenter registered for the channel, else the core default;
 *   `core` = the core default; a module name = that module's presenter (the core default while it is not registered).
 * - `notifications.quietHours`: `HH:mm-HH:mm` ("" = off): inside it only urgent notifications reach the OS channel.
 * - `notifications.dedupeWindowSec`: the dedupe window (0 = no dedupe).
 * - `notifications.historyMax`: notifications kept in the history (inbox).
 * - `notifications.titleMaxChars`, `notifications.bodyMaxChars`: a daemon-side notification's title and body are cut to these
 *   lengths before they cross the wire.
 */
data class NotificationSettings(
    val routes: Map<NotificationLevel, List<String>> = DEFAULT_ROUTES,
    val presenters: Map<String, String> = emptyMap(),
    val quietHours: String = "",
    val dedupeWindowSec: Int = DEFAULT_DEDUPE_WINDOW_SEC,
    val historyMax: Int = DEFAULT_HISTORY_MAX,
    val titleMaxChars: Int = DEFAULT_TITLE_MAX_CHARS,
    val bodyMaxChars: Int = DEFAULT_BODY_MAX_CHARS,
) {
    fun route(level: NotificationLevel): List<String> = routes[level] ?: DEFAULT_ROUTES.getValue(level)
    fun presenter(channel: String): String = presenters[channel].orEmpty()

    /** Whether [minuteOfDay] (0..1439, local time) is inside [quietHours]; a blank or unreadable value is never quiet. */
    fun quiet(minuteOfDay: Int?): Boolean {
        val (from, to) = parseQuietHours(quietHours) ?: return false
        val m = minuteOfDay ?: return false
        return if (from <= to) m in from until to else m >= from || m < to
    }

    companion object {
        const val PREFIX = "notifications."
        const val ROUTE_PREFIX = "notifications.route."
        const val PRESENTER_PREFIX = "notifications.presenter."
        const val QUIET_HOURS = "notifications.quietHours"
        const val DEDUPE_WINDOW_SEC = "notifications.dedupeWindowSec"
        const val HISTORY_MAX = "notifications.historyMax"
        const val TITLE_MAX_CHARS = "notifications.titleMaxChars"
        const val BODY_MAX_CHARS = "notifications.bodyMaxChars"

        const val DEFAULT_DEDUPE_WINDOW_SEC = 600
        const val MAX_DEDUPE_WINDOW_SEC = 86_400
        const val DEFAULT_HISTORY_MAX = 100
        const val MAX_HISTORY_MAX = 10_000
        const val DEFAULT_TITLE_MAX_CHARS = 200
        const val MAX_TITLE_MAX_CHARS = 10_000
        const val DEFAULT_BODY_MAX_CHARS = 4000
        const val MAX_BODY_MAX_CHARS = 1_000_000
        const val MINUTES_PER_DAY = 1440

        val DEFAULT_ROUTES: Map<NotificationLevel, List<String>> = NotificationLevel.entries.associateWith {
            if (it == NotificationLevel.URGENT) listOf(NotificationChannels.IN_APP, NotificationChannels.OS) else listOf(NotificationChannels.IN_APP)
        }

        /** Every key: the route of each level, the presenter of each built-in channel, the rest. */
        val KEYS: List<String> = NotificationLevel.entries.map { ROUTE_PREFIX + it.wire } +
            NotificationChannels.BUILT_IN.map { PRESENTER_PREFIX + it } + listOf(QUIET_HOURS, DEDUPE_WINDOW_SEC, HISTORY_MAX, TITLE_MAX_CHARS, BODY_MAX_CHARS)

        fun isKey(key: String): Boolean = key in KEYS

        /** A route value as its channel list: comma-separated, `none` / blank = none. */
        fun parseRoute(text: String): List<String> =
            text.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("none", true) }.distinct()

        /** `HH:mm-HH:mm` as (from, to) minutes of day; null when blank or unreadable. */
        fun parseQuietHours(text: String): Pair<Int, Int>? {
            val parts = text.trim().split('-').map { it.trim() }
            if (parts.size != 2) return null
            fun minute(s: String): Int? {
                val hm = s.split(':')
                if (hm.size != 2) return null
                val h = hm[0].toIntOrNull() ?: return null
                val m = hm[1].toIntOrNull() ?: return null
                return if (h in 0..23 && m in 0..59) h * 60 + m else null
            }
            val from = minute(parts[0]) ?: return null
            val to = minute(parts[1]) ?: return null
            return from to to
        }

        /**
         * [value] for [key] as it is stored, or an [IllegalArgumentException] that says what is wanted. Core's settings
         * service calls this for every `notifications.*` key.
         */
        fun validated(key: String, value: JsonElement): JsonElement {
            val p = value as? JsonPrimitive
            return when {
                key == DEDUPE_WINDOW_SEC -> JsonPrimitive(int(key, p, 0..MAX_DEDUPE_WINDOW_SEC))
                key == HISTORY_MAX -> JsonPrimitive(int(key, p, 1..MAX_HISTORY_MAX))
                key == TITLE_MAX_CHARS -> JsonPrimitive(int(key, p, 1..MAX_TITLE_MAX_CHARS))
                key == BODY_MAX_CHARS -> JsonPrimitive(int(key, p, 1..MAX_BODY_MAX_CHARS))
                key == QUIET_HOURS -> {
                    val t = p?.takeIf { it.isString }?.content?.trim()
                        ?: throw IllegalArgumentException("setting '$key' must be a string HH:mm-HH:mm (\"\" = off)")
                    require(t.isEmpty() || parseQuietHours(t) != null) { "setting '$key' must be HH:mm-HH:mm, e.g. 22:00-07:00 (\"\" = off)" }
                    JsonPrimitive(t)
                }
                key.startsWith(ROUTE_PREFIX) && key in KEYS -> {
                    val t = p?.takeIf { it.isString }?.content
                        ?: throw IllegalArgumentException("setting '$key' must be a string of channels, e.g. \"inApp,os\" (\"none\" = history only)")
                    JsonPrimitive(parseRoute(t).joinToString(",").ifEmpty { "none" })
                }
                key.startsWith(PRESENTER_PREFIX) && key in KEYS -> JsonPrimitive(
                    p?.takeIf { it.isString }?.content?.trim()
                        ?: throw IllegalArgumentException("setting '$key' must be a string: \"\" (auto), \"core\" or a module name"),
                )
                else -> throw IllegalArgumentException("unknown setting '$key'")
            }
        }

        private fun int(key: String, p: JsonPrimitive?, range: IntRange): Int {
            val n = p?.takeIf { !it.isString }?.content?.toIntOrNull()
            require(n != null && n in range) { "setting '$key' must be a JSON integer in $range" }
            return n
        }

        /** Reads the settings from a flat key -> JSON map (the daemon's settings, or a client's SETTINGS frame). */
        fun from(values: Map<String, JsonElement>): NotificationSettings {
            fun text(k: String): String? = (values[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun num(k: String): Int? = (values[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            val routes = NotificationLevel.entries.associateWith { l -> text(ROUTE_PREFIX + l.wire)?.let(::parseRoute) ?: DEFAULT_ROUTES.getValue(l) }
            val presenters = values.keys.filter { it.startsWith(PRESENTER_PREFIX) && it.length > PRESENTER_PREFIX.length }
                .associate { it.removePrefix(PRESENTER_PREFIX) to text(it).orEmpty().trim() }
            return NotificationSettings(
                routes = routes,
                presenters = presenters,
                quietHours = text(QUIET_HOURS).orEmpty(),
                dedupeWindowSec = num(DEDUPE_WINDOW_SEC)?.coerceIn(0, MAX_DEDUPE_WINDOW_SEC) ?: DEFAULT_DEDUPE_WINDOW_SEC,
                historyMax = num(HISTORY_MAX)?.coerceIn(1, MAX_HISTORY_MAX) ?: DEFAULT_HISTORY_MAX,
                titleMaxChars = num(TITLE_MAX_CHARS)?.coerceIn(1, MAX_TITLE_MAX_CHARS) ?: DEFAULT_TITLE_MAX_CHARS,
                bodyMaxChars = num(BODY_MAX_CHARS)?.coerceIn(1, MAX_BODY_MAX_CHARS) ?: DEFAULT_BODY_MAX_CHARS,
            )
        }

        /** The SETTINGS frame text as settings; null when it carries no `notifications.*` key (the frame changed something else). */
        fun fromSettingsFrame(data: String): NotificationSettings? {
            val o = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
            if (o.keys.none { it.startsWith(PREFIX) }) return null
            return from(o)
        }

        /** The keys and their effective values, for the settings `all()` map. */
        fun json(s: NotificationSettings): Map<String, JsonElement> =
            NotificationLevel.entries.associate { ROUTE_PREFIX + it.wire to JsonPrimitive(s.route(it).joinToString(",").ifEmpty { "none" }) } +
                NotificationChannels.BUILT_IN.associate { PRESENTER_PREFIX + it to JsonPrimitive(s.presenter(it)) } +
                s.presenters.filterKeys { it !in NotificationChannels.BUILT_IN }.entries.associate { PRESENTER_PREFIX + it.key to JsonPrimitive(it.value) } +
                mapOf(QUIET_HOURS to JsonPrimitive(s.quietHours), DEDUPE_WINDOW_SEC to JsonPrimitive(s.dedupeWindowSec), HISTORY_MAX to JsonPrimitive(s.historyMax),
                    TITLE_MAX_CHARS to JsonPrimitive(s.titleMaxChars), BODY_MAX_CHARS to JsonPrimitive(s.bodyMaxChars))
    }
}

/**
 * The frames of the bus between the daemon and a client. Plain JSON in the envelope's `data`.
 *
 * - [PUSH] daemon -> client: one [Notification] a daemon-side caller posted; also replayed on connect for the sticky ones not dismissed.
 * - [ACTION] client -> daemon: a pressed button ([NotificationActionEvent]); the daemon tells its listeners and publishes [EVENT_ACTION].
 * - [DISMISS] client -> daemon: `{"id": ...}`, so the daemon stops replaying it.
 */
object NotificationWire {
    const val PUSH = "notification_push"
    const val ACTION = "notification_action"
    const val DISMISS = "notification_dismiss"
    val CLIENT_FRAMES = setOf(ACTION, DISMISS)

    /** The daemon event (hook name) a pressed action publishes: payload `notificationId`, `actionId`, `source`. */
    const val EVENT_ACTION = "notification.action"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(n: Notification): String = json.encodeToString(Notification.serializer(), n)
    fun encode(e: NotificationActionEvent): String = json.encodeToString(NotificationActionEvent.serializer(), e)
    fun dismiss(id: String): String = JsonObject(mapOf("id" to JsonPrimitive(id))).toString()

    fun notification(data: String): Notification? = runCatching { json.decodeFromString(Notification.serializer(), data) }.getOrNull()
    fun action(data: String): NotificationActionEvent? = runCatching { json.decodeFromString(NotificationActionEvent.serializer(), data) }.getOrNull()
    fun dismissId(data: String): String? =
        runCatching { ((json.parseToJsonElement(data) as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
}
