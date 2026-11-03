// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import kotlin.math.ceil

/**
 * A shell tool call that is only waiting: the agent ran `sleep N` (or the PowerShell / cmd equivalent) and is blocked
 * for [seconds]. Read from the agent's own tool call, so it costs the agent nothing. [startedAt] is when the daemon saw
 * the call; [source] is always [SOURCE_SLEEP] today.
 */
data class WaitHint(val seconds: Long, val startedAt: Long, val source: String = SOURCE_SLEEP) {
    companion object {
        const val SOURCE_SLEEP = "sleep"
    }
}

/**
 * The one place that recognises a sleep in a shell command. Pure: no clock, no I/O.
 *
 * Takes the first segment (split on `&&`, `||`, `;`, `|`, newline) that is a sleep and reads only that segment, so
 * `make; sleep 90; echo` waits 90s and the meter never claims the whole command is the sleep. Recognised:
 * `sleep N` / `Ns` / `Nm` / `Nh` / `Nd` (decimals, several args add up), `Start-Sleep N` / `-Seconds N` / `-s N` /
 * `-Milliseconds N` / `-m N`, `timeout /t N`. Not a hint: 0, negative, `infinity`, `$VAR`, anything non-numeric, a
 * background call, a command with a loop (each iteration is its own wait), and a sleep under [MIN_SECONDS].
 * The shown wait is capped at the call's own timeout, when it names one.
 */
object SleepHint {
    /** A shorter sleep is not worth a meter. */
    const val MIN_SECONDS = 5L

    private val SHELL_TOOLS = setOf("bash", "powershell", "shell", "sh", "cmd")
    private val SEGMENT_SPLIT = Regex("""&&|\|\||;|\||\r?\n""")
    private val QUOTED = Regex(""""[^"]*"|'[^']*'""")
    private val LOOP = Regex("""(?i)\b(while|for|until|foreach|loop)\b""")
    private val DURATION = Regex("""(\d+(?:\.\d+)?)([smhd]?)""")

    fun parse(tool: String, command: String?, background: Boolean = false, timeoutSeconds: Long? = null): Long? {
        if (background || command.isNullOrBlank() || tool.lowercase() !in SHELL_TOOLS) return null
        if (LOOP.containsMatchIn(QUOTED.replace(command, " "))) return null
        val seconds = command.split(SEGMENT_SPLIT).firstNotNullOfOrNull { segment(it) } ?: return null
        val capped = if (timeoutSeconds != null && timeoutSeconds > 0) minOf(seconds, timeoutSeconds) else seconds
        return capped.takeIf { it >= MIN_SECONDS }
    }

    private fun segment(raw: String): Long? {
        val t = raw.trim().split(Regex("""\s+"""))
        val head = t.firstOrNull()?.lowercase() ?: return null
        val args = t.drop(1)
        val s = when (head) {
            "sleep" -> gnu(args)
            "start-sleep" -> powershell(args)
            "timeout" -> cmdTimeout(args)
            else -> null
        } ?: return null
        return ceil(s).toLong().takeIf { it > 0 }
    }

    private fun gnu(args: List<String>): Double? {
        if (args.isEmpty()) return null
        var sum = 0.0
        for (a in args) {
            val m = DURATION.matchEntire(a.lowercase()) ?: return null
            sum += m.groupValues[1].toDouble() * when (m.groupValues[2]) { "m" -> 60; "h" -> 3600; "d" -> 86400; else -> 1 }
        }
        return sum
    }

    private fun powershell(args: List<String>): Double? {
        if (args.isEmpty()) return null
        val first = args[0].lowercase()
        val (unit, value) = when {
            first in setOf("-seconds", "-s") -> 1.0 to args.getOrNull(1)
            first in setOf("-milliseconds", "-m", "-ms") -> 0.001 to args.getOrNull(1)
            !first.startsWith("-") -> 1.0 to args[0]
            else -> return null
        }
        return value?.toDoubleOrNull()?.takeIf { it > 0 }?.times(unit)
    }

    /** `timeout /t 30 [/nobreak]`; GNU `timeout 5 cmd` (a limit on another command) is not a wait. */
    private fun cmdTimeout(args: List<String>): Double? {
        val i = args.indexOfFirst { it.equals("/t", ignoreCase = true) }
        if (i < 0) return null
        return args.getOrNull(i + 1)?.toDoubleOrNull()?.takeIf { it > 0 }
    }
}
