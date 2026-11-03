// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

/**
 * How one speaker is shown beside their message in a transcript: two initials on a colour, plus an
 * optional custom image (null until a later ticket). Pure data, no Compose: rendering is separate.
 */
data class AvatarSpec(val initials: String, val colorArgb: Int, val imageUrl: String? = null)

/** Reserved identity of the human in a transcript. */
const val USER_AVATAR_ID = "user"

/**
 * A curated palette of saturated hues. A fixed palette (rather than a free-running hue) keeps the
 * murky colours out and the avatars calm next to the dark UI; the identity's hash picks one entry.
 */
private val AVATAR_PALETTE = intArrayOf(
    0xFFE07A5F.toInt(), 0xFF3D9BE9.toInt(), 0xFF81B29A.toInt(), 0xFFB08BD8.toInt(),
    0xFFF2CC8F.toInt(), 0xFF6BBF59.toInt(), 0xFFE56399.toInt(), 0xFF4EC9B0.toInt(),
    0xFFD98E48.toInt(), 0xFF8AB4F8.toInt(), 0xFFC58AF9.toInt(), 0xFFD96459.toInt(),
)

/** The user's own line: a neutral disc, deliberately outside the saturated agent palette. */
private val USER_COLOR = 0xFF8B909A.toInt()

/**
 * The avatar for a speaker, derived purely from the identity so the same speaker is the same colour
 * and initials on every render (live, replayed or after a reconnect). [imageUrl] stays null for now:
 * custom images are a later ticket.
 */
fun avatarFor(agentId: String, name: String, role: String? = null): AvatarSpec {
    if (agentId == USER_AVATAR_ID) return AvatarSpec(initialsOf(name, "You"), USER_COLOR, null)
    val index = (("$agentId|$name|${role ?: ""}".hashCode().toLong() and 0x7fffffffL) % AVATAR_PALETTE.size).toInt()
    return AvatarSpec(initialsOf(name, agentId), AVATAR_PALETTE[index], null)
}

/** Up to two initials, one per word ("Scrum Master" -> "SM"). Falls back to [fallback] when blank. */
private fun initialsOf(name: String, fallback: String): String {
    val words = name.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }
    val initials = words.take(2).mapNotNull { it.firstOrNull() }.joinToString("")
    return initials.ifEmpty { fallback.trim() }.uppercase().take(2).ifEmpty { "?" }
}
