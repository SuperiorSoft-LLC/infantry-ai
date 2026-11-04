// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlinx.datetime.toLocalDateTime

/** The one place a clock time is formatted for display: 12-hour with AM/PM, e.g. `2:59 PM`. */
object TimeFormat {
    /** `h:mm AM/PM`; midnight is 12:00 AM and noon 12:00 PM. */
    fun clock12(hour: Int, minute: Int): String {
        val h = if (hour % 12 == 0) 12 else hour % 12
        return "$h:${minute.toString().padStart(2, '0')} ${if (hour < 12) "AM" else "PM"}"
    }

    /** `h:mm:ss AM/PM`. */
    fun clock12(hour: Int, minute: Int, second: Int): String =
        clock12(hour, minute).replace(" ", ":${second.toString().padStart(2, '0')} ")
}

/** LOCAL-TIME-1: the one human date-time formatter: `Nov 3, 8:41 PM` in [zone] (default the system zone). Storage stays UTC epoch ms. */
fun formatLocal(epochMs: Long, zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault()): String {
    val t = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    val mon = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[t.monthNumber - 1]
    return "$mon ${t.dayOfMonth}, ${TimeFormat.clock12(t.hour, t.minute)}"
}
