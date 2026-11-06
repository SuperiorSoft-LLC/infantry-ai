// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * FIRST-RUN-F1 (ticket 771ccf07): an interactive claude that never finished its first-run setup (theme, sign in) opens on that
 * setup screen instead of its prompt, and a turn pasted into it waits forever. Its global config file says whether the setup was
 * done (`hasCompletedOnboarding`), so the launch fails at once with [NOT_SIGNED_IN] instead.
 *
 * The check blocks only on proof: the file is missing, or it is a JSON object without `hasCompletedOnboarding: true`. A file that
 * cannot be read or parsed, or a setup this code does not know (a custom OAuth URL names a different file), never blocks.
 */
object ClaudeSignIn {
    const val NOT_SIGNED_IN = "Claude Code is not signed in. Run `claude` once in a terminal and sign in, then restart this agent."

    /** The CLI's own choice: a non-empty `.config.json` in its config dir (legacy), else `.claude.json` in CLAUDE_CONFIG_DIR or the home folder. */
    fun configFile(env: Map<String, String>, home: File): File {
        val custom = env["CLAUDE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let(::File)
        val legacy = File(custom ?: File(home, ".claude"), ".config.json")
        if (legacy.isFile && legacy.length() > 0) return legacy
        return File(custom ?: home, ".claude.json")
    }

    /** [NOT_SIGNED_IN] when the config proves the first-run setup was never finished; null otherwise (and on any doubt). */
    fun problem(env: Map<String, String>, home: File): String? {
        if (!env["CLAUDE_CODE_CUSTOM_OAUTH_URL"].isNullOrBlank()) return null
        val file = configFile(env, home)
        if (!file.exists()) return NOT_SIGNED_IN
        val config = runCatching { TtyJson.json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return null
        val done = (config["hasCompletedOnboarding"] as? JsonPrimitive)?.content == "true"
        return if (done) null else NOT_SIGNED_IN
    }

    /**
     * The backstop: the setup screens themselves, spaces squeezed out and lower-cased (the theme picker and the login method menu).
     * Read only before the CLI ran its SessionStart hook, so a conversation that mentions them later never counts.
     */
    val SETUP_SCREEN_MARKERS = listOf("choosethetextstylethatlooksbestwithyourterminal", "selectloginmethod")
}
