// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.EffectiveEffort
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord

/**
 * EFFORT-1: what the reasoning effort looks like on screen, shared by the New agent dialog, the ticket forms, the board
 * card and the session chip; the same shape as the context limit (see ContextLimitModel). How a harness honours a level
 * comes from the daemon (`supported` / `best_effort` / `unsupported`, plus how).
 */
object EffortLevels {
    data class Level(val id: String, val label: String)

    /** The levels in order, mirroring the daemon's `session.EffortLevels.ALL`; a new level is one more entry here. */
    val ALL: List<Level> = listOf(
        Level("low", "Low"), Level("medium", "Medium"), Level("high", "High"), Level("xhigh", "Extra high"), Level("max", "Max"),
    )

    fun label(id: String): String = ALL.firstOrNull { it.id == id.trim().lowercase() }?.label ?: id
}

/** The support line under the field: [harness]'s [support] in words, with the how. The one place the wording lives. */
fun effortSupportNote(harness: String, support: String?, how: String?): String {
    val h = how?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return when (support) {
        "supported" -> "$harness applies the effort$h"
        "best_effort" -> "$harness applies it best effort$h"
        else -> "$harness does not apply an effort (it is recorded, not applied)$h"
    }
}

/** The harness [harness]'s entry when the daemon says how it honours an effort; null = no harness chosen or an older daemon. */
fun effortProvider(harnesses: List<ProviderInfo>, harness: String?): ProviderInfo? =
    harness?.let { h -> harnesses.firstOrNull { it.id == h } }?.takeIf { it.effort != null }

/** The line under the field for the chosen [harness] (null = none chosen). */
fun effortNoteFor(harnesses: List<ProviderInfo>, harness: String?): String {
    val info = effortProvider(harnesses, harness)
        ?: return if (harness == null) "Pick a harness to see whether it honours an effort." else "This harness does not report whether it honours an effort."
    return effortSupportNote(info.id, info.effort, info.effortHow)
}

/** The session / card chip: `effort high`, `effort high (best effort)` or `effort high (not applied)`; null when none is set. */
data class EffortChip(val text: String, val tooltip: String)

fun effortChip(level: String?, support: String?, how: String? = null, source: String = "session"): EffortChip? {
    if (level.isNullOrBlank()) return null
    val name = level.trim().lowercase()
    val text = when (support) {
        "unsupported" -> "effort $name (not applied)"
        "best_effort" -> "effort $name (best effort)"
        else -> "effort $name"
    }
    val note = when (support) {
        "supported" -> "applied by the harness"
        "best_effort" -> "applied best effort"
        "unsupported" -> "recorded, not applied by this harness"
        else -> "whether this harness applies it is not reported"
    }
    val h = how?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return EffortChip(text, "Reasoning effort ${EffortLevels.label(name)}, set on the $source; $note$h")
}

/** The chip for [session]; the how text is its harness's, from the `providers` frame. */
fun sessionEffortChip(session: SessionRecord, providers: List<ProviderInfo>): EffortChip? =
    effortChip(session.effort, session.effortSupport, providers.firstOrNull { it.id == session.provider }?.effortHow)

/** The board card's line: `effort high (ticket)` or `effort default (default)`; null when the daemon sent none. */
fun effortLine(e: EffectiveEffort?): String? {
    if (e == null) return null
    val v = e.value?.takeIf { it.isNotBlank() } ?: "default"
    val flag = when (e.support) { "unsupported" -> " - not applied"; "best_effort" -> " - best effort"; else -> "" }
    return "effort $v (${e.source})${if (e.value != null) flag else ""}"
}

/** The `ticket_save.effort` an edit sends: null = unchanged (left out), "" = clear (off), else the new level. */
fun ticketEffortEdit(before: String?, after: String?): String? = if (before == after) null else after ?: ""
