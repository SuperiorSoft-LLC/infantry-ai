// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.roles.daemon

/**
 * The role palette, and the two derivations the generated-fallback avatar needs: a colour and
 * initials.
 *
 * This is load-bearing, not decorative: the product must work with **zero** bundled art, so when
 * neither an operator override nor a bundled asset exists the badge is painted from the role's
 * colour and initials. Nothing here reads the bundled manifest; a built-in role's own colour comes
 * from the manifest, and this palette is only what an unknown or de-manifested id falls back to.
 */
object RolePalette {
    /**
     * Colours for ids the bundle does not name. Deliberately a small, legible set that reads on both
     * a light and a dark row; a generated colour is picked from here by a stable hash of the id, so
     * the same id is always the same colour on every machine.
     */
    val FALLBACK: List<String> = listOf(
        "#6366f1", // indigo
        "#0ea5e9", // sky
        "#22c55e", // green
        "#14b8a6", // teal
        "#ef4444", // red
        "#f97316", // orange
        "#8b5cf6", // violet
        "#eab308", // amber
        "#ec4899", // pink
        "#06b6d4", // cyan
        "#d946ef", // fuchsia
        "#64748b", // slate
    )

    /** The colour used when a role is unknown: a deterministic pick from [FALLBACK]. */
    fun colorFor(roleId: String): String = FALLBACK[Math.floorMod(roleId.hashCode(), FALLBACK.size)]

    /**
     * Up to two uppercase initials for a role id, taken from the **first and last** word:
     * `chief-of-staff` -> `CS` (reads better than `CO`), `backend` -> `B`, `i18n` -> `I`.
     * Hyphens, underscores, spaces and dots separate words; a leading non-letter is dropped so a
     * stray id still yields something paintable. An empty result is impossible: any non-blank id
     * yields at least one character, and a blank id yields `?`.
     */
    fun initials(roleId: String): String {
        val words = roleId.split('-', '_', ' ', '.')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val letters = words.mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
            .map { it.uppercaseChar() }
        if (letters.isEmpty()) return "?"
        return if (letters.size == 1) letters[0].toString() else "${letters.first()}${letters.last()}"
    }
}
