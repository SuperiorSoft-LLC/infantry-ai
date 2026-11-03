// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * MCP-1: the `infantry mcp serve` entry every session the daemon spawns gets, so an agent reaches its board (tickets,
 * mail, sessions) through MCP tools instead of shell commands.
 *
 * THE LAUNCHER is the same `infantry` the daemon already puts first on the session's PATH ([InfantryBin]): the session
 * env's [InfantryBin.BIN_ENV], else [InfantryBin.resolve]. On Windows the launcher is a `.cmd` / `.bat`, which a client
 * cannot spawn directly, so the entry is `cmd /c <launcher> mcp serve`. No launcher found = the bare `infantry` on PATH.
 *
 * THE ENV. The MCP server is a child of the harness, which inherits the session env; the entry ALSO names the
 * session's identity ([IDENTITY_ENV]: session, project, agent, role, daemon address) explicitly, so a harness that
 * starts MCP servers with a clean environment still serves the right session. The bearer token is never written into
 * the entry (it would land in a file / argv): it is inherited.
 *
 * MERGED, NEVER OVERWRITTEN: a user config that already defines an `infantry` server keeps its own.
 */
object InfantryMcp {
    const val SERVER_NAME = "infantry"

    /** Claude's `--allowedTools` rule for every tool of [SERVER_NAME] (`mcp__<server>__<tool>`). */
    const val CLAUDE_TOOL_RULE = "mcp__${SERVER_NAME}__*"

    /** The session-identity variables written into the entry's env when the session env has them. */
    val IDENTITY_ENV: List<String> = listOf(
        AccessPolicy.SESSION_ID_ENV,
        AccessPolicy.PROJECT_ID_ENV,
        AccessPolicy.AGENT_ID_ENV,
        AccessPolicy.ROLE_ENV,
        AccessPolicy.MAIL_BASE_ENV,
        InfantryBin.URL_ENV,
        InfantryBin.BIN_ENV,
    )

    /** How to start `infantry mcp serve` for one session. */
    data class Launch(val command: String, val args: List<String>, val env: Map<String, String>) {
        /** The argv as one list (opencode's `command` array). */
        val argv: List<String> get() = listOf(command) + args
    }

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** The launch for a session whose env is [sessionEnv]. */
    fun launch(
        sessionEnv: Map<String, String>,
        binDir: File? = sessionEnv[InfantryBin.BIN_ENV]?.takeIf { it.isNotBlank() }?.let(::File) ?: InfantryBin.resolve(),
        windows: Boolean = isWindows,
    ): Launch {
        val launcher = binDir?.let { dir ->
            (if (windows) listOf("infantry.cmd", "infantry.bat") else listOf("infantry")).map { File(dir, it) }.firstOrNull { it.isFile }
        }?.absolutePath ?: "infantry"
        val serve = listOf("mcp", "serve")
        val env = IDENTITY_ENV.mapNotNull { k -> sessionEnv[k]?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
        return if (windows) Launch("cmd", listOf("/c", launcher) + serve, env) else Launch(launcher, serve, env)
    }

    // ---- Claude --------------------------------------------------------------------------------------------------

    /** The Claude `--mcp-config` JSON for [launch]: `{"mcpServers":{"infantry":{type, command, args, env}}}`. */
    fun claudeConfigJson(launch: Launch): String = JsonObject(mapOf(
        "mcpServers" to JsonObject(mapOf(SERVER_NAME to JsonObject(mapOf(
            "type" to JsonPrimitive("stdio"),
            "command" to JsonPrimitive(launch.command),
            "args" to JsonArray(launch.args.map(::JsonPrimitive)),
            "env" to JsonObject(launch.env.mapValues { JsonPrimitive(it.value) }),
        )))),
    )).toString()

    /**
     * The `--mcp-config` values for a session: [userConfigs] (`INFANTRY_CLAUDE_MCP_CONFIG`, kept first and unchanged)
     * plus the infantry entry, unless one of them already defines an `infantry` server. The entry is passed as a FILE
     * ([configFile]), not inline: a JVM `ProcessBuilder` on Windows does not escape the quotes inside an argument, so an
     * inline JSON object reaches claude.exe as `{mcpServers:{...}}` (verified on JDK 21).
     */
    fun claudeMcpConfigs(
        userConfigs: List<String>,
        launch: Launch,
        configFile: (String) -> String = ::configFile,
    ): List<String> =
        if (userConfigs.any(::definesInfantryClaude)) userConfigs else userConfigs + configFile(claudeConfigJson(launch))

    /** Whether one `--mcp-config` value (inline JSON or a file path) already names an `infantry` server. */
    internal fun definesInfantryClaude(value: String): Boolean {
        val text = if (value.trimStart().startsWith("{")) value else runCatching { File(value).readText() }.getOrNull() ?: return false
        val servers = parse(text)?.get("mcpServers") as? JsonObject ?: return false
        return SERVER_NAME in servers
    }

    /**
     * Writes [json] to a content-addressed file (`<tmp>/infantry-mcp/infantry-<sha>.json`) and returns its path: the same
     * content is the same path, so a session's launch argv (and its restart fingerprint) is stable across turns.
     */
    fun configFile(json: String, dir: File = File(System.getProperty("java.io.tmpdir"), "infantry-mcp")): String {
        val sha = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val file = File(dir, "infantry-$sha.json")
        if (!file.isFile || file.readText() != json) {
            dir.mkdirs()
            file.writeText(json)
        }
        return file.absolutePath
    }

    private fun parse(text: String?): JsonObject? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
}
