// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * LOCAL-TIME-1: the one place the daemon turns a stored instant (epoch ms, UTC) into a time a person reads.
 * The zone is the `display.timeZone` setting, blank = the system zone. Storage and wire values stay UTC.
 */
object DisplayZone {
    @Volatile var zone: ZoneId = ZoneId.systemDefault()

    /** Apply the setting value; blank or null = the system zone. Throws [IllegalArgumentException] on an unknown id. */
    fun set(id: String?) {
        zone = if (id.isNullOrBlank()) ZoneId.systemDefault() else try { ZoneId.of(id.trim()) } catch (e: Exception) {
            throw IllegalArgumentException("unknown time zone '$id' (use an id like America/New_York, or leave blank for the system zone)")
        }
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")

    /** `2026-11-03 20:41 -05:00`: local time with its offset, for agent-facing lines. */
    fun stamp(epochMs: Long, z: ZoneId = zone): String = STAMP.format(Instant.ofEpochMilli(epochMs).atZone(z))

    /** The calendar day `yyyy-MM-dd` of [epochMs] in [z]: local midnight to local midnight. */
    fun day(epochMs: Long, z: ZoneId = zone): String = Instant.ofEpochMilli(epochMs).atZone(z).toLocalDate().toString()
}
