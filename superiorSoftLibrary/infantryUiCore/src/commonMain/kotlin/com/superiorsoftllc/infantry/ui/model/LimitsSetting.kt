package com.superiorsoftllc.infantry.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * No-hard-coded-limits (contract 1.133.0): one daemon limit as the `limit_catalog` frame describes it. [value] is the
 * one in force (stored setting, else env, else default); a change is a `settings_set` the daemon validates.
 */
data class LimitInfo(
    val key: String,
    val value: Long,
    val default: Long,
    val unit: String,
    val description: String,
    val min: Long,
    val max: Long?,
    /** What 0 means when the limit can be turned off (`unlimited`, `no deadline`, ...); null = 0 is not special. */
    val zeroMeans: String?,
    val env: String,
) {
    fun accepts(v: Long): Boolean = v >= min && (max == null || v <= max)

    /** The range line under the field, e.g. `0..4096 workers; 0 = unlimited; default 4`. */
    fun rangeText(): String = "$min.." + (max?.toString() ?: "no ceiling") + " $unit" +
        (zeroMeans?.let { "; 0 = $it" } ?: "") + "; default $default; env $env"

    /** The group shown as a heading: `limits.watchdog.stallMinutes` -> `watchdog`. */
    val group: String get() = key.removePrefix(LimitsSetting.PREFIX).substringBefore('.')

    /** The name shown in the row: `limits.watchdog.stallMinutes` -> `stallMinutes`. */
    val name: String get() = key.removePrefix(LimitsSetting.PREFIX).substringAfter('.')
}

object LimitsSetting {
    const val PREFIX = "limits."

    /** The first contract whose daemon answers `limit_catalog`. */
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 133

    const val TITLE = "Limits"
    const val APPLIES_TO = "Every cap, threshold and timeout of this daemon; applied at once, kept across restarts"

    /** The catalog in a `limit_catalog` frame's data; null when it cannot be read. */
    fun fromCatalogFrame(data: String): List<LimitInfo>? {
        val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val items = obj["limits"] as? JsonArray ?: return null
        return items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            LimitInfo(
                key = s("key") ?: return@mapNotNull null,
                value = l("value") ?: return@mapNotNull null,
                default = l("default") ?: 0,
                unit = s("unit").orEmpty(),
                description = s("description").orEmpty(),
                min = l("min") ?: 0,
                max = l("max"),
                zeroMeans = s("zeroMeans"),
                env = s("env").orEmpty(),
            )
        }
    }

    /** [catalog] with the values a `settings` frame carries for its keys (a frame without them changes nothing). */
    fun withSettingsFrame(catalog: List<LimitInfo>?, data: String): List<LimitInfo>? {
        if (catalog == null) return null
        val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return catalog
        return catalog.map { info ->
            val v = (obj[info.key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            if (v == null || v == info.value) info else info.copy(value = v)
        }
    }

    /** A typed field's value, or null when it is not a whole number [info] accepts (the field then shows the range). */
    fun parse(info: LimitInfo, text: String): Long? = text.trim().toLongOrNull()?.takeIf(info::accepts)
}
