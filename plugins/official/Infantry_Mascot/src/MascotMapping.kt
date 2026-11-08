// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mascot

import com.maxmaster.materialmascot.config.MascotFinish
import com.maxmaster.materialmascot.engine.BotState
import com.maxmaster.materialmascot.engine.EyeStyle
import com.superiorsoftllc.infantry.ui.AvatarMotion
import com.superiorsoftllc.infantry.ui.AvatarRequest

/** MASCOT-2: the pure part of the style: session state -> mascot state, role -> look, config text -> data. No Compose. */
object MascotMapping {
    /** The default state map (the module.json default of `mascot.stateMap`): session state name -> mascot mood name. */
    val DEFAULT_STATE_MAP: Map<String, String> = linkedMapOf(
        "STARTING" to "Greet", "BUSY" to "Working", "WAITING_INPUT" to "Notify", "IDLE" to "Idle",
        "EXITED" to "Sleepy", "ORPHANED" to "Confused", "FAILED" to "Alert",
    )

    /** `STATE=Mood;STATE=Mood` over the defaults; a malformed pair is skipped. */
    fun parseStateMap(text: String?): Map<String, String> {
        val out = LinkedHashMap(DEFAULT_STATE_MAP)
        for ((k, v) in pairs(text)) out[k.uppercase()] = v
        return out
    }

    /** The mood for the session state of [request]: [map] first, Idle for no session, an unknown state or an unknown mood. */
    fun stateFor(request: AvatarRequest, map: Map<String, String> = DEFAULT_STATE_MAP, happy: Boolean = false): BotState {
        val session = request.state?.uppercase() ?: return BotState.Idle
        if (request.busyDetail == "error") return BotState.Alert
        if (session == "BUSY" && request.busyDetail == "compacting") return BotState.Reading
        if (session == "EXITED" && happy) return BotState.Happy
        val mood = map[session] ?: return BotState.Idle
        return BotState.all.firstOrNull { it.name.equals(mood, ignoreCase = true) } ?: BotState.Idle
    }

    /** Whether this request animates: `all` everywhere, `focus` where the site passed LIVE, `static` never. */
    fun live(mode: String?, motion: AvatarMotion): Boolean = when (mode) {
        "all" -> true
        "focus" -> motion == AvatarMotion.LIVE
        else -> false
    }

    /** Eyes, finish and ears of one role. */
    data class Look(val eyes: EyeStyle, val finish: MascotFinish, val cat: Boolean)

    /** [overrides] (`role=eyes/finish/cat`) first, else a deterministic look from a hash of the role id. */
    fun lookFor(role: String?, overrides: Map<String, Look> = emptyMap()): Look {
        val id = (role ?: "").lowercase()
        overrides[id]?.let { return it }
        var h = 5381L
        for (c in id) h = (h * 33 + c.code) and 0x7fffffffL
        val eyes = EyeStyle.entries[(h % EyeStyle.entries.size).toInt()]
        val finish = MascotFinish.entries[((h / 5) % MascotFinish.entries.size).toInt()]
        return Look(eyes, finish, cat = (h / 15) % 3 == 0L)
    }

    fun parseRoleLooks(text: String?): Map<String, Look> {
        val out = LinkedHashMap<String, Look>()
        for ((role, spec) in pairs(text)) {
            val parts = spec.split('/').map { it.trim().lowercase() }
            val eyes = EyeStyle.entries.firstOrNull { it.name.lowercase() == parts.getOrNull(0) } ?: continue
            val finish = MascotFinish.entries.firstOrNull { it.name.lowercase() == parts.getOrNull(1) } ?: continue
            out[role.lowercase()] = Look(eyes, finish, cat = parts.getOrNull(2) == "cat")
        }
        return out
    }

    private fun pairs(text: String?): List<Pair<String, String>> = (text ?: "").split(';').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
    }
}
