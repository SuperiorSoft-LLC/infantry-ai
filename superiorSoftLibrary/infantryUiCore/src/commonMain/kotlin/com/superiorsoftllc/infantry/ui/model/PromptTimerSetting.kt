// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * ASK-TIMER-2 (ticket cc73295b): Settings > Tool approval > "Prompt timer". How long an approval / question card waits
 * for the owner (`prompts.timeoutSec`, 0 = Indefinite) and, when the asker recommended no option, what an unanswered
 * tool approval resolves as (`prompts.onTimeout`). Daemon state: the shown values are the daemon's last `settings` frame,
 * a change is a `settings_set` the daemon validates.
 */
object PromptTimerSetting {
    const val KEY_TIMEOUT = "prompts.timeoutSec"
    const val KEY_ON_TIMEOUT = "prompts.onTimeout"

    /** The first contract whose daemon takes `prompts.timeoutSec` 0 and `prompts.onTimeout` (ASK-TIMER, c62e72a9). */
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 139

    const val MAX_SEC = 86_400

    /** The value the control suggests; only a suggestion, the daemon keeps whatever is stored. */
    const val SUGGESTED_SEC = 120

    const val TITLE = "Prompt timer"
    const val APPLIES_TO = "When nobody answers an approval or question card, the timer auto-selects the option the agent " +
        "recommended. Applies to every session, ticket workers included, unless a session was started with its own timer"

    /** The fallbacks `prompts.onTimeout` takes. */
    val ON_TIMEOUT_CHOICES = listOf("deny", "approve")

    /** The values a settings frame carries; a null field = the frame did not say. */
    data class Value(val timeoutSec: Int? = null, val onTimeout: String? = null)

    /** "Indefinite", "2 min", "90 s". */
    fun label(seconds: Int): String = when {
        seconds == 0 -> "Indefinite (wait for my answer)"
        seconds % 3_600 == 0 -> "${seconds / 3_600} h"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }

    fun onTimeoutLabel(value: String): String = when (value) {
        "deny" -> "Deny the tool"
        "approve" -> "Allow the tool once"
        else -> value
    }

    /** What the seconds field holds, typed: a whole number 0..[MAX_SEC], or null (refused, nothing is sent). */
    fun parseSeconds(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 0..MAX_SEC }

    /** [previous] with the timer keys of a `settings` frame's [data] over it; null when the frame carries neither. */
    fun fromSettingsFrame(data: String, previous: Value?): Value? {
        val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val seconds = (obj[KEY_TIMEOUT] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 0..MAX_SEC }
        val onTimeout = (obj[KEY_ON_TIMEOUT] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (seconds == null && onTimeout == null) return null
        val base = previous ?: Value()
        return Value(seconds ?: base.timeoutSec, onTimeout ?: base.onTimeout)
    }
}
