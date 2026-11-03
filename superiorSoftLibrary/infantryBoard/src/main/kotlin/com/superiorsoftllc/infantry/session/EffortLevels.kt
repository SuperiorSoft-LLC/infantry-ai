// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

/**
 * EFFORT-1 (docs/EFFORT.md): the one ordered set of reasoning-effort levels, as DATA. Spawn, agent profiles, role bindings,
 * routing rules and tickets all validate against [ALL]; a harness maps a level to its own flag (or does not, see
 * [com.superiorsoftllc.infantry.harness.EffortCapability]). Unset (null) = the harness's own default, which is the off switch.
 */
object EffortLevels {
    /** One level: the [id] sent to harnesses and over the wire, and the [label] a person reads. */
    data class Level(val id: String, val label: String)

    /** Lowest to highest. Add or reorder here only: nothing else names a level. */
    val ALL: List<Level> = listOf(
        Level("low", "Low"),
        Level("medium", "Medium"),
        Level("high", "High"),
        Level("xhigh", "Extra high"),
        Level("max", "Max"),
    )

    val ids: List<String> get() = ALL.map { it.id }

    /** Null when [effort] is unset or a known level, else why not. */
    fun problem(effort: String?): String? =
        if (effort == null || ALL.any { it.id == effort }) null else "unknown effort '${effort.take(40)}': one of $ids"

    /** A user-typed level: blank, `off`, `none`, `default` = unset (null); a level in any case; else an error. */
    fun parse(raw: String?): Parsed {
        val text = raw?.trim()?.lowercase().orEmpty()
        if (text.isEmpty() || text == "off" || text == "none" || text == "default") return Parsed(null, cleared = true)
        return problem(text)?.let { Parsed(null, error = it) } ?: Parsed(text)
    }

    data class Parsed(val value: String?, val cleared: Boolean = false, val error: String? = null)
}
