// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pi.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * HARNESS-ALL-1: whether pi can answer on this machine, for the runner Check (`Harness.signInProblem`). pi has no login of its own: it
 * answers with a provider key from its `auth.json` (`/login`), from its environment (`ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, ...), or
 * through a provider an extension or `models.json` adds. A fresh runner container has none of them, and the Check said "signed in".
 *
 * Proof only, never doubt: [problem] is non-null only when pi's agent dir (`PI_CODING_AGENT_DIR`, default `~/.pi/agent`) holds no
 * `auth.json` entry, no `models.json` and no extension, and no provider key variable is set. A file that cannot be read counts as a login.
 * A key the app lends with a start is not in this env; the Check names that case itself.
 */
object PiSignIn {
    const val AGENT_DIR_ENV = "PI_CODING_AGENT_DIR"

    const val NO_PROVIDER =
        "pi has no provider key or login on this machine, so a session would start and the model could not answer. " +
            "Set a key for pi in the app (Settings > Harness keys), or run `pi` there once and use /login."

    /** Provider credentials named by variable, not by the `_API_KEY` suffix (which every other provider key carries). */
    private val OTHER_KEY_VARS = setOf("ANTHROPIC_OAUTH_TOKEN", "HF_TOKEN", "AWS_ACCESS_KEY_ID", "AWS_PROFILE", "AWS_BEARER_TOKEN_BEDROCK", "GOOGLE_APPLICATION_CREDENTIALS")

    private val json = Json { ignoreUnknownKeys = true }

    fun agentDir(env: Map<String, String>, home: File): File =
        env[AGENT_DIR_ENV]?.takeIf { it.isNotBlank() }?.let(::File) ?: File(File(home, ".pi"), "agent")

    private fun hasKeyVar(env: Map<String, String>): Boolean =
        env.any { (k, v) -> v.isNotBlank() && (k.endsWith("_API_KEY") || k in OTHER_KEY_VARS) }

    /** True when `auth.json` holds at least one entry, or exists and cannot be read as an object (doubt). */
    private fun hasAuthEntry(dir: File): Boolean {
        val f = File(dir, "auth.json")
        if (!f.isFile) return false
        return runCatching { (json.parseToJsonElement(f.readText().removePrefix("﻿")) as JsonObject).isNotEmpty() }.getOrDefault(true)
    }

    private fun hasExtraProviders(dir: File): Boolean =
        File(dir, "models.json").isFile || File(dir, "extensions").listFiles()?.isNotEmpty() == true

    /** [NO_PROVIDER] when there is proof pi cannot answer here; null otherwise. */
    fun problem(env: Map<String, String>, home: File): String? {
        if (hasKeyVar(env)) return null
        val dir = agentDir(env, home)
        if (hasAuthEntry(dir) || hasExtraProviders(dir)) return null
        return NO_PROVIDER
    }
}
