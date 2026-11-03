// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Project-scoped defaults for `infantry mcp serve` (Phase 11, slice P11-C, docs/MCP.md).
 *
 * Two sources, merged by [merge]: the command line (`--project`, `--max-depth`, `--max-spawns`,
 * `--allow-kill-any`) and an OPTIONAL `.infantry/mcp.json` in the server's working directory. A flag wins over
 * the file; a value neither sets follows the daemon settings `limits.concurrency.maxDepth` / `mcpMaxSpawns`.
 *
 * WHY A FILE. An MCP client (Claude Code, Codex, ...) starts the server from its own config, usually checked
 * into the repo, with the repo root as the working directory. A project file lets that one shared config line
 * stay `infantry mcp serve` while each repo pins its own project id and limits.
 *
 * STRICT ON PURPOSE. Unknown keys and wrong types are refused, not ignored: a typo such as `"maxSpawn": 1`
 * silently falling back to the default of 4 would widen a guardrail the author meant to narrow. A refusal is
 * an [McpConfigException], which the CLI turns into one `error:` line on stderr and exit 2 -- before the server
 * ever answers `initialize`, so the MCP client shows the server as failed rather than half-configured.
 *
 * Null fields mean "not set by this source".
 */
internal data class McpServeConfig(
    /** Default `projectId` for `infantry_spawn` and `infantry_pod_state` when the call does not pass one. */
    val projectId: String? = null,
    val maxDepth: Int? = null,
    val maxSpawns: Int? = null,
    val allowKillAny: Boolean? = null,
) {
    companion object {
        /** Relative to the server's working directory. */
        const val RELATIVE_PATH = ".infantry/mcp.json"
        /** RENAME-2: the pre-rename location, read when [RELATIVE_PATH] is absent. */
        const val LEGACY_RELATIVE_PATH = ".agenthq/mcp.json"

        val KEYS = setOf("projectId", "maxDepth", "maxSpawns", "allowKillAny")

        fun fileIn(cwd: File): File = File(cwd, RELATIVE_PATH).takeIf { it.isFile } ?: File(cwd, LEGACY_RELATIVE_PATH).takeIf { it.isFile } ?: File(cwd, RELATIVE_PATH)

        /** The file in [cwd], or null when there is none. Throws [McpConfigException] when it is unusable. */
        fun load(cwd: File): McpServeConfig? {
            val file = fileIn(cwd)
            if (!file.exists()) return null
            val text = try {
                file.readText()
            } catch (e: Exception) {
                throw McpConfigException(file, "cannot be read (${e.message ?: e.javaClass.simpleName})")
            }
            return parse(text, file)
        }

        fun parse(text: String, file: File): McpServeConfig {
            val root = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw McpConfigException(file, "is not valid JSON (${e.message?.lineSequence()?.firstOrNull() ?: "parse error"})")
            }
            if (root !is JsonObject) throw McpConfigException(file, "must be a JSON object, e.g. {\"projectId\": \"my-project\"}")
            val unknown = root.keys - KEYS
            if (unknown.isNotEmpty()) {
                throw McpConfigException(
                    file,
                    "has unknown key(s) ${unknown.sorted().joinToString(", ") { "'$it'" }}; allowed: ${KEYS.joinToString(", ")}",
                )
            }
            fun primitive(key: String): JsonPrimitive? = when (val v = root[key]) {
                null, JsonNull -> null
                is JsonPrimitive -> v
                else -> throw McpConfigException(file, "'$key' must be a ${if (key == "projectId") "string" else "plain value"}, got $v")
            }
            fun count(key: String): Int? = primitive(key)?.let { v ->
                v.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }
                    ?: throw McpConfigException(file, "'$key' must be a whole number >= 0, got $v")
            }
            val projectId = primitive("projectId")?.let { v ->
                v.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw McpConfigException(file, "'projectId' must be a non-empty string, got $v")
            }
            val allowKillAny = primitive("allowKillAny")?.let { v ->
                v.takeIf { !it.isString }?.booleanOrNull
                    ?: throw McpConfigException(file, "'allowKillAny' must be true or false, got $v")
            }
            return McpServeConfig(projectId, count("maxDepth"), count("maxSpawns"), allowKillAny)
        }

        /**
         * Flags over file. `--allow-kill-any` is a presence flag, so it can only turn the wider kill scope ON:
         * [flags].allowKillAny is `true` or null, and a file's `true` is not narrowed by an absent flag.
         */
        fun merge(flags: McpServeConfig, file: McpServeConfig?): McpServeConfig = McpServeConfig(
            projectId = flags.projectId ?: file?.projectId,
            maxDepth = flags.maxDepth ?: file?.maxDepth,
            maxSpawns = flags.maxSpawns ?: file?.maxSpawns,
            allowKillAny = flags.allowKillAny ?: file?.allowKillAny,
        )
    }
}

/** A `.infantry/mcp.json` that cannot be used; the message names the file and says what is wrong. */
internal class McpConfigException(file: File, problem: String) :
    RuntimeException("${McpServeConfig.RELATIVE_PATH} (${file.absolutePath}) $problem")
