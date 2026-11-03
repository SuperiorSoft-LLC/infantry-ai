// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * BUG-M2-11e (docs/INFANTRY-SESSION-HOST-PLAN.md section 8): the ONE session-lifecycle setting, `session.keepAliveMinutes`.
 * It is daemon state, not window state: the value shown is the one the daemon last answered with, and a change is a
 * `settings_set` the daemon validates and stores. It applies to every harness, model and session.
 */
object LifecycleSetting {
    /** The daemon's key (infantry `SettingsService.KEEP_ALIVE_MINUTES`). */
    const val KEY = "session.keepAliveMinutes"

    /** The first contract whose daemon knows [KEY] (BUG-M2-11b-2). An older one would refuse the key. */
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 68

    const val MAX_MINUTES = 1440

    const val TITLE = "Session lifecycle"
    const val APPLIES_TO = "Applies to every harness, model and session on this daemon"

    /** The choices the control offers; a stored value outside them is shown as-is and added to the list. */
    val PRESETS: List<Int> = listOf(0, 5, 15, 30, 60, 120, 240, MAX_MINUTES)

    /** What the control shows for [minutes] (plan 11e): 0 is off, N > 0 names the idle window. */
    fun label(minutes: Int): String =
        if (minutes == 0) "Off (start a process per reply)" else "Keep each session's process for $minutes idle minutes"

    /** The choices for a control currently holding [current] (null = not read yet). */
    fun options(current: Int?): List<Int> =
        if (current == null || current in PRESETS) PRESETS else (PRESETS + current).sorted()

    /** The value of [KEY] in a `settings` frame's data, or null when the frame does not carry it (or is not an int). */
    fun fromSettingsFrame(data: String): Int? {
        val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val value = obj[KEY] as? JsonPrimitive ?: return null
        if (value.isString) return null
        return value.intOrNull?.takeIf { it in 0..MAX_MINUTES }
    }
}
