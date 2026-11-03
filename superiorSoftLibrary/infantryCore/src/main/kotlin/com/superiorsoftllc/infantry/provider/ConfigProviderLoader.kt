// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * PROV-1: loads provider definitions from a directory of JSON files (e.g. `~/.infantry/providers/`)
 * and wires each one into [ProviderFactory], so a provider can be added by dropping a file with no
 * recompile.
 *
 * The definition shape is deliberately the smallest thing that works:
 * `{"id":"aider","command":"aider","args":["--message-file","-"]}`.
 *
 * Scope, stated so the deferred pieces are not mistaken for oversights: [args] are passed to
 * [ConfigProvider] as a TEMPLATE, so `{model}`/`{session}`/`{workdir}`/`{prompt}` fill per run
 * (PROV-2); a configurable session-id regex is PROV-4. The one rule that IS enforced here is that a
 * file may never shadow a built-in id -- that guard lives in [ProviderFactory.registerIfAbsent], the
 * single place provider names are owned.
 *
 * PROV-3 closes the failure contract: a file that does not parse, or that is missing a required
 * field (`id`/`command`), is NOT silently skipped -- [load] refuses, naming the offending file(s).
 * A directory can hold several files, so every valid one still loads and the refusal comes AFTER,
 * naming the malformed ones: a bad drop never silently does nothing and never takes a good provider
 * down with it.
 */
class ConfigProviderLoader(private val dir: File) {

    /**
     * Read [dir] and wire every provider definition found. A missing directory is a no-op.
     *
     * Throws [IllegalArgumentException] if any `.json` file is malformed (invalid JSON, or a blank
     * `id`/`command`), after registering every file that was well-formed. The message names each
     * offending file, so the operator knows exactly which drop to fix.
     */
    fun load() {
        if (!dir.isDirectory) return
        val malformed = dir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()
            .filterNot(::register)
            .map { it.name }
        if (malformed.isNotEmpty()) {
            throw IllegalArgumentException(
                "Malformed provider file(s) in ${dir.path}: ${malformed.joinToString(", ")} " +
                    "(each needs valid JSON with a non-empty \"id\" and \"command\").",
            )
        }
    }

    /** Wire [file] if well-formed; report whether it was. Never throws for bad data. */
    private fun register(file: File): Boolean {
        val definition = runCatching { json.decodeFromString<Definition>(file.readText()) }
            .getOrNull() ?: return false
        val id = definition.id.trim()
        if (id.isEmpty() || definition.command.trim().isEmpty()) return false
        val regex = definition.sessionIdRegex?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { Regex(raw) }.getOrElse { return false }
        }
        ProviderFactory.registerIfAbsent(id) {
            ConfigProvider(id, definition.command, definition.args, regex)
        }
        return true
    }

    /** The on-disk shape of one provider definition. */
    @Serializable
    private data class Definition(
        val id: String,
        val command: String,
        val args: List<String> = emptyList(),
        val sessionIdRegex: String? = null,
    )

    private companion object {
        const val SUFFIX = ".json"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
