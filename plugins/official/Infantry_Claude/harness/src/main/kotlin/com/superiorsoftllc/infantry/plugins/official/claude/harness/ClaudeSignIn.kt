// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * FIRST-RUN-F1 (ticket 771ccf07): an interactive claude that never finished its first-run setup (theme, sign in) opens on that
 * setup screen instead of its prompt, and a turn pasted into it would wait forever. Its global config file says whether the setup
 * was done (`hasCompletedOnboarding`).
 *
 * RUNNER-LOGIN-1 (ticket 27c0a4ec): `claude auth login` (the login the runner docs give, run by `docker exec`) writes the login
 * (`.credentials.json`) but NOT `hasCompletedOnboarding`, so the old onboarding-only check said "not signed in" for a CLI whose
 * `claude auth status` says loggedIn. Now:
 * * [problem] (the runner Check's `loggedIn`, re-read on every Check) is null when the setup was done OR the CLI holds a login.
 * * [prepare] (every launch) never blocks: with a login and an unfinished setup it marks the setup done, as a first interactive run
 *   would, so the CLI opens on its prompt. With no login at all the launch still goes ahead and the setup-screen backstop
 *   ([SETUP_SCREEN_MARKERS]) fails the turn with [NOT_SIGNED_IN] and the screen.
 *
 * Doubt never counts as "not signed in": a file that cannot be read or parsed, or a setup this code does not know (a custom OAuth
 * URL names a different file).
 */
object ClaudeSignIn {
    const val NOT_SIGNED_IN = "Claude Code is not signed in. Run `claude` once in a terminal and sign in, then restart this agent."

    /** The env var a subscription token (`claude setup-token`) signs the CLI in with. */
    const val OAUTH_TOKEN_ENV = "CLAUDE_CODE_OAUTH_TOKEN"

    private fun customDir(env: Map<String, String>): File? = env["CLAUDE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }?.let(::File)

    /** The CLI's own choice: a non-empty `.config.json` in its config dir (legacy), else `.claude.json` in CLAUDE_CONFIG_DIR or the home folder. */
    fun configFile(env: Map<String, String>, home: File): File {
        val custom = customDir(env)
        val legacy = File(custom ?: File(home, ".claude"), ".config.json")
        if (legacy.isFile && legacy.length() > 0) return legacy
        return File(custom ?: home, ".claude.json")
    }

    /** Where the CLI keeps its login on Linux and Windows: `.credentials.json` in CLAUDE_CONFIG_DIR, else in `~/.claude`. */
    fun credentialsFile(env: Map<String, String>, home: File): File = File(customDir(env) ?: File(home, ".claude"), ".credentials.json")

    /** The CLI holds a login here: its credentials file, or a subscription token in the env. */
    fun hasLogin(env: Map<String, String>, home: File): Boolean =
        !env[OAUTH_TOKEN_ENV].isNullOrBlank() || credentialsFile(env, home).let { it.isFile && it.length() > 0 }

    private sealed interface Setup {
        object Missing : Setup
        object Unknown : Setup
        data class Read(val config: JsonObject) : Setup { val done get() = (config["hasCompletedOnboarding"] as? JsonPrimitive)?.content == "true" }
    }

    private fun setup(file: File): Setup {
        if (!file.exists()) return Setup.Missing
        return runCatching { TtyJson.json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()?.let { Setup.Read(it) } ?: Setup.Unknown
    }

    /** [NOT_SIGNED_IN] when there is proof claude cannot answer here (setup never finished and no login); null otherwise (and on any doubt). */
    fun problem(env: Map<String, String>, home: File): String? {
        if (!env["CLAUDE_CODE_CUSTOM_OAUTH_URL"].isNullOrBlank()) return null
        if (hasLogin(env, home)) return null
        return when (val s = setup(configFile(env, home))) {
            Setup.Missing -> NOT_SIGNED_IN
            Setup.Unknown -> null
            is Setup.Read -> if (s.done) null else NOT_SIGNED_IN
        }
    }

    /**
     * Before a launch: a CLI that holds a login but never finished its first-run setup gets `hasCompletedOnboarding: true` in its
     * config (merged into the file; the file is made when missing), so it opens on its prompt and not on the theme picker. Touches
     * nothing without a login, with a finished setup, or with a file it cannot parse. Never throws; true = the file was written.
     */
    fun prepare(env: Map<String, String>, home: File): Boolean = runCatching {
        if (!env["CLAUDE_CODE_CUSTOM_OAUTH_URL"].isNullOrBlank() || !hasLogin(env, home)) return false
        val file = configFile(env, home)
        val config = when (val s = setup(file)) {
            Setup.Missing -> JsonObject(emptyMap())
            Setup.Unknown -> return false
            is Setup.Read -> if (s.done) return false else s.config
        }
        val next = JsonObject(config + ("hasCompletedOnboarding" to JsonPrimitive(true)))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".infantry-tmp")
        tmp.writeText(TtyJson.json.encodeToString(JsonObject.serializer(), next))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        true
    }.getOrDefault(false)

    /**
     * The backstop: the setup screens themselves, spaces squeezed out and lower-cased (the theme picker and the login method menu).
     * Read only before the CLI ran its SessionStart hook, so a conversation that mentions them later never counts.
     */
    val SETUP_SCREEN_MARKERS = listOf("choosethetextstylethatlooksbestwithyourterminal", "selectloginmethod")
}
