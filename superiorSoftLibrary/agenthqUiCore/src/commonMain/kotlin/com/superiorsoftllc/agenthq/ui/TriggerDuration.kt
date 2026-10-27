package com.superiorsoftllc.agenthq.ui

import kotlin.math.floor
import kotlin.math.roundToLong

/** Minutes a user typed (whole or decimal, e.g. "2" or "0.5") as wire milliseconds; null when it is not a non-negative number. */
internal fun minutesToMs(text: String): Long? {
    val minutes = text.trim().toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() } ?: return null
    return (minutes * 60_000.0).roundToLong()
}

/** Wire milliseconds as the minutes a form field shows: "2", "0.5", "0". Legacy sub-minute rows keep 3 places. */
internal fun msToMinutesText(ms: Long): String {
    val minutes = ms / 60_000.0
    return if (minutes == floor(minutes)) minutes.toLong().toString() else ((minutes * 1000).roundToLong() / 1000.0).toString()
}

/** A friendly label for a period: "30 min", "2 h", "1 d"; legacy rows below a minute still read ("45 s"). */
// S4c: kit (docs/SHELL-LEAKS.md), a duration formatter on core's internal msToMinutesText; Infantry_Triggers draws it.
fun humanDuration(ms: Long): String = when {
    ms <= 0L -> "none"
    ms < 1000L -> "$ms ms"
    ms < 60_000L -> "${ms / 1000} s"
    ms % 86_400_000L == 0L -> "${ms / 86_400_000L} d"
    ms % 3_600_000L == 0L -> "${ms / 3_600_000L} h"
    ms % 60_000L == 0L -> "${ms / 60_000L} min"
    else -> "${msToMinutesText(ms)} min"
}
