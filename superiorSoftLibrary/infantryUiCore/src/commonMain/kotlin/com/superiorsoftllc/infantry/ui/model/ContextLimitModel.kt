// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.EffectiveContextLimit
import com.superiorsoftllc.infantry.ui.protocol.ProviderInfo
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord

/**
 * CONTEXT-LIMIT-2: what the context limit looks like on screen, shared by the New agent dialog, the ticket editor, the
 * board card and the session chip. The parsing (`200k`, `1m`) is [ContextLimitInput]; how a harness honours a limit
 * comes from the daemon (`supported` / `best_effort` / `unsupported`, plus how).
 */
object ContextLimitPresets {
    /** The one-click sizes next to the field; data, so a new size is one more entry. */
    val ALL: List<Int> = listOf(200_000, 500_000, 1_000_000)
}

/** 200000 -> `200k`, 1000000 -> `1M`, 1500000 -> `1.5M`, 150000 -> `150k`, 12345 -> `12345`. */
fun formatContextLimit(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 100_000 == 0 -> (tokens / 100_000).let { if (it % 10 == 0) "${it / 10}M" else "${it / 10}.${it % 10}M" }
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}k"
    else -> tokens.toString()
}

/** The support line under the field: [harness]'s [support] in words, with the how. The one place the wording lives. */
fun contextLimitSupportNote(harness: String, support: String?, how: String?): String {
    val h = how?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return when (support) {
        "supported" -> "$harness applies the limit$h"
        "best_effort" -> "$harness applies it best effort$h"
        else -> "$harness does not apply a limit (it is recorded, not applied)$h"
    }
}

/** The harness [harness]'s entry when the daemon says how it honours a limit; null = no harness chosen or an older daemon. */
fun contextLimitProvider(harnesses: List<ProviderInfo>, harness: String?): ProviderInfo? =
    harness?.let { h -> harnesses.firstOrNull { it.id == h } }?.takeIf { it.contextLimit != null }

/** The line under the field for the chosen [harness] (null = none chosen). */
fun contextLimitNoteFor(harnesses: List<ProviderInfo>, harness: String?): String {
    val info = contextLimitProvider(harnesses, harness)
        ?: return if (harness == null) "Pick a harness to see whether it honours a limit." else "This harness does not report whether it honours a limit."
    return contextLimitSupportNote(info.id, info.contextLimit, info.contextLimitHow)
}

/**
 * The session / card chip: `limit 1M`, `limit 1M (best effort)` or `limit 200k (not applied)`; null when no limit is set.
 * [tooltip] says where the value came from ([source]) and the how. A later live meter fills this same chip.
 */
data class ContextLimitChip(val text: String, val tooltip: String)

fun contextLimitChip(limit: Int?, support: String?, how: String? = null, source: String = "session"): ContextLimitChip? {
    if (limit == null || limit <= 0) return null
    val size = formatContextLimit(limit)
    val text = when (support) {
        "unsupported" -> "limit $size (not applied)"
        "best_effort" -> "limit $size (best effort)"
        else -> "limit $size"
    }
    val note = when (support) {
        "supported" -> "applied by the harness"
        "best_effort" -> "applied best effort"
        "unsupported" -> "recorded, not applied by this harness"
        else -> "whether this harness applies it is not reported"
    }
    val h = how?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return ContextLimitChip(text, "Context limit $size, set on the $source; $note$h")
}

/** The chip for [session]; the how text is its harness's, from the `providers` frame. */
fun sessionContextLimitChip(session: SessionRecord, providers: List<ProviderInfo>): ContextLimitChip? =
    contextLimitChip(session.contextLimit, session.contextLimitSupport, providers.firstOrNull { it.id == session.provider }?.contextLimitHow)

/** The board card's line: `context limit 1M (ticket)` or `context limit default (default)`; null when the daemon sent none. */
fun contextLimitLine(e: EffectiveContextLimit?): String? {
    if (e == null) return null
    val v = e.value?.takeIf { it > 0 }?.let { formatContextLimit(it) } ?: "default"
    val flag = when (e.support) { "unsupported" -> " - not applied"; "best_effort" -> " - best effort"; else -> "" }
    return "context limit $v (${e.source})${if (e.value != null) flag else ""}"
}

/** The `ticket_save.contextLimit` an edit sends: null = unchanged (left out), 0 = clear, else the new size. */
fun ticketContextLimitEdit(before: Int?, after: Int?): Int? = if (before == after) null else after ?: 0
