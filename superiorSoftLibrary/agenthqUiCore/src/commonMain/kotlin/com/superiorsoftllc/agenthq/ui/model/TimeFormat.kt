package com.superiorsoftllc.agenthq.ui.model

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
