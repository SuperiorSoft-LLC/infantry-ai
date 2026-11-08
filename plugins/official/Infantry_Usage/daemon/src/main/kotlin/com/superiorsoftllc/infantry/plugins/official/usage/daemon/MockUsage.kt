// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.usage.daemon

import com.superiorsoftllc.infantry.daemon.api.usage.LimitWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * USAGE-MOCK-1: the mock plan meters. `usage.mock.data` is a JSON object, provider id to its meters:
 * `{"claude": {"session": 18, "week": 42, "monthly": 7, "weekResetsInMinutes": 3000}}`. `session`, `week` and `monthly` are
 * percents (0..100; absent or null = no such window); `<meter>ResetsInMinutes` is the reset, relative to the read (default: half the
 * window). Nothing about an account, a login or an email can be expressed, so the snapshot is safe to share.
 */
object MockUsage {
    const val SETTING = "usage.mock"
    const val DATA_SETTING = "usage.mock.data"
    const val PACING_SETTING = "usage.mock.affectsPacing"
    const val ENV = "INFANTRY_USAGE_MOCK"
    const val DATA_ENV = "INFANTRY_USAGE_MOCK_DATA"
    const val PACING_ENV = "INFANTRY_USAGE_MOCK_AFFECTS_PACING"

    /** What shows when the mock is on and `usage.mock.data` holds nothing readable. */
    const val DEFAULT_DATA = """{"claude":{"session":18,"week":42,"monthly":null},"opencode-go":{"session":30,"week":12,"monthly":5}}"""

    private val meters = listOf(
        Triple("session", "Current session", 300),
        Triple("week", "Current week", 10_080),
        Triple("monthly", "Monthly", 43_200),
    )

    fun on(value: String?): Boolean = value?.trim()?.lowercase() in setOf("1", "true", "on", "yes")

    /** The windows per provider at [now]; unreadable data gives [DEFAULT_DATA]'s. Providers with no window are left out. */
    fun windows(data: String?, now: Long): Map<String, List<LimitWindow>> {
        val parsed = parse(data) ?: parse(DEFAULT_DATA)!!
        return parsed.mapNotNull { (provider, meter) ->
            val obj = (meter as? JsonObject) ?: return@mapNotNull null
            val list = meters.mapNotNull { (key, label, minutes) ->
                val percent = (obj[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..100.0 } ?: return@mapNotNull null
                val resets = ((obj[key + "ResetsInMinutes"] as? JsonPrimitive)?.doubleOrNull ?: (minutes / 2.0)).coerceIn(0.0, minutes.toDouble())
                val elapsed = 100.0 * (1.0 - resets / minutes)
                LimitWindow(provider, label, minutes, percent, now + (resets * 60_000).toLong(), now, "live", Math.round((percent - elapsed) * 10) / 10.0)
            }
            if (list.isEmpty()) null else provider to list
        }.toMap()
    }

    private fun parse(data: String?): JsonObject? =
        data?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
}
