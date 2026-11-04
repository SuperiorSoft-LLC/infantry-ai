// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.ProcessToolProfile

/**
 * STACK-NEUTRAL-2: the regexes the watch builds from the tool profiles' data (`api.processes.toolProfiles()`). What is a build
 * daemon, a shared one, its client and a heavy runtime is read from the profiles; no profile = matches nothing (the generic treatment).
 */
internal object ProfileRx {
    private val NEVER = Regex("(?!)")

    private fun anyOf(parts: List<String>): Regex =
        if (parts.isEmpty()) NEVER else Regex("(?i)" + parts.distinct().joinToString("|") { "(?:$it)" })

    /** One regex over every profile's detached daemons. */
    fun daemons(list: List<ProcessToolProfile>): Regex {
        val parts = list.flatMap { it.daemons }
        return if (parts.isEmpty()) NEVER else Regex("(?i)" + parts.joinToString("|") { "(?:$it)" })
    }

    /** The daemons of `sharedDaemon` profiles. */
    fun sharedDaemons(list: List<ProcessToolProfile>): Regex = daemons(list.filter { it.sharedDaemon })

    /** The name of the profile whose daemons match [command], or null. */
    fun daemonProfile(command: String, list: List<ProcessToolProfile>): String? =
        list.firstOrNull { p -> p.daemons.isNotEmpty() && daemons(listOf(p)).containsMatchIn(command) }?.name

    fun clients(list: List<ProcessToolProfile>): Regex = anyOf(list.flatMap { it.clients })

    /** PROC-LEAK-1: every profile's `stuck` client regexes. */
    fun stuck(list: List<ProcessToolProfile>): Regex = anyOf(list.flatMap { it.stuck })

    /** PROC-LEAK-1: one regex over [parts]; use with [Regex.containsMatchIn]. */
    fun any(parts: List<String>): Regex = anyOf(parts)

    /** Process images worth listing when unattributed; use with [Regex.matches]. */
    fun heavyImages(list: List<ProcessToolProfile>): Regex {
        val parts = list.flatMap { it.heavyImages }.distinct()
        return if (parts.isEmpty()) NEVER else Regex("(?i)(?:" + parts.joinToString("|") { "(?:$it)" } + ")(?:\\.exe)?")
    }

    /** Command lines the ledger may adopt by worktree path; use with [Regex.containsMatchIn]. */
    fun heavyCommand(list: List<ProcessToolProfile>): Regex = anyOf(list.flatMap { it.heavyCommands })
}
