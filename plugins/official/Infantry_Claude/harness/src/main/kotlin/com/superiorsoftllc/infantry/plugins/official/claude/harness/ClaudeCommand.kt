// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.ContextLimitCapability
import com.superiorsoftllc.infantry.harness.api.ContextLimitSupport
import com.superiorsoftllc.infantry.harness.api.EffortCapability
import com.superiorsoftllc.infantry.harness.api.EffortSupport
import com.superiorsoftllc.infantry.harness.api.HarnessCommandAction
import com.superiorsoftllc.infantry.harness.api.HarnessCommandSpec
import com.superiorsoftllc.infantry.harness.api.HarnessModels
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * HARNESS H7b-2: the Claude Code invocation, written against the harness API only. A twin of the daemon's built-in
 * `claude.ClaudeCommand` / `ClaudePermissionMode` / `ClaudeEffort` / `ClaudeContextLimit` / `ClaudeCommands` / `ClaudeModelCatalog`
 * (the plugin cannot see them); `ClaudePluginParityTest` in the daemon's tests pins the two to the same output.
 *
 * Shape: `claude -p [--input-format stream-json] --output-format stream-json --verbose --thinking adaptive --thinking-display summarized
 * [--model m] [--effort e] [--permission-mode pm] --allowedTools rules [--permission-prompt-tool stdio] [--settings f]
 * [--mcp-config f... [--strict-mcp-config]] [--resume id]`; the prompt goes to stdin. Facts encoded here and not to be "simplified":
 * the NATIVE claude executable is spawned (never an npm shim); `--verbose` is required with stream-json in `-p` mode; `-p` never prompts, so
 * a tool outside the allowlist is denied silently and the permission mode decides what a session may do.
 */
object ClaudeCommand {
    const val CLI_ID = "claude"

    /**
     * CLI-LIVE-1: module setting (`harness.claude.liveIdleMinutes`): minutes a RESTING live claude process may idle before it is stopped.
     * 0 (default) = never. MEMORY COST: each live process holds roughly 150-400 MB of RAM for as long as the session exists, so many
     * long-lived sessions with 0 add up; set N to reclaim memory from sessions nobody has woken for N minutes (the next wake resumes).
     */
    const val LIVE_IDLE_SETTING = "harness.claude.liveIdleMinutes"
    const val HARNESS_ID = "claude"

    /** The infantry MCP server's name (the session's own `infantry mcp serve`). */
    const val MCP_SERVER_NAME = "infantry"

    /** `--permission-prompt-tool` value that routes permission checks over the stream-json control channel. */
    const val PERMISSION_PROMPT_STDIO = "stdio"

    /** The `--allowedTools` rules of every mode, deny included: the infantry CLI (control plane) and the session's own MCP server. */
    fun allowedTools(): List<String> = listOf("Bash(infantry:*)", "PowerShell(infantry:*)", "Bash(agenthq:*)", "PowerShell(agenthq:*)", mcpToolRule(MCP_SERVER_NAME))

    fun mcpToolRule(serverName: String): String = "mcp__${serverName}__*"

    /** deny / ask = no flag (Claude's default), allowEdits = acceptEdits, allowAll = bypassPermissions. `INFANTRY_CLAUDE_PERMISSION_MODE` still wins. */
    fun permissionMode(
        approval: ToolApproval,
        legacy: String? = System.getProperty("INFANTRY_CLAUDE_PERMISSION_MODE") ?: System.getenv("INFANTRY_CLAUDE_PERMISSION_MODE"),
    ): String? {
        legacy?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return when (approval) {
            ToolApproval.DENY, ToolApproval.ASK -> null
            ToolApproval.ALLOW_EDITS -> "acceptEdits"
            ToolApproval.ALLOW_ALL -> "bypassPermissions"
        }
    }

    fun approvalArgs(approval: ToolApproval): List<String> = permissionMode(approval)?.let { listOf("--permission-mode", it) } ?: emptyList()

    /** `INFANTRY_CLAUDE_MCP_CONFIG`: file paths or inline JSON, separated by commas or newlines. */
    fun parseMcpConfigs(raw: String?): List<String> = raw.orEmpty().split(',', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }

    fun userMcpConfigs(): List<String> = parseMcpConfigs(System.getenv("INFANTRY_CLAUDE_MCP_CONFIG"))

    fun strictMcp(): Boolean = System.getenv("INFANTRY_CLAUDE_STRICT_MCP")?.trim()?.lowercase() in setOf("1", "true", "yes")

    fun defaultModel(): String? = System.getenv("INFANTRY_CLAUDE_MODEL")?.takeIf { it.isNotBlank() }

    fun effectiveModel(requested: String?): String? = requested?.takeIf { it.isNotBlank() } ?: defaultModel()

    /** The `--mcp-config` JSON for the infantry server launched by [command] (first element = executable) with [env]. */
    fun mcpConfigJson(command: List<String>, env: Map<String, String>): String = JsonObject(
        mapOf(
            "mcpServers" to JsonObject(
                mapOf(
                    MCP_SERVER_NAME to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("stdio"),
                            "command" to JsonPrimitive(command.first()),
                            "args" to JsonArray(command.drop(1).map(::JsonPrimitive)),
                            "env" to JsonObject(env.mapValues { JsonPrimitive(it.value) }),
                        ),
                    ),
                ),
            ),
        ),
    ).toString()

    /** Whether one `--mcp-config` value (inline JSON or a file) already names an `infantry` server. */
    fun definesInfantry(value: String): Boolean {
        val text = if (value.trimStart().startsWith("{")) value else runCatching { File(value).readText() }.getOrNull() ?: return false
        val servers = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.get("mcpServers") as? JsonObject ?: return false
        return MCP_SERVER_NAME in servers
    }

    /** User configs unchanged, plus the infantry entry as a content-addressed FILE (inline JSON loses its quotes on Windows) unless one defines it. */
    fun mcpConfigs(
        userConfigs: List<String>,
        mcpCommand: List<String>?,
        mcpEnv: Map<String, String>,
        configFile: (String) -> String = ::configFile,
    ): List<String> =
        if (mcpCommand.isNullOrEmpty() || userConfigs.any(::definesInfantry)) userConfigs else userConfigs + configFile(mcpConfigJson(mcpCommand, mcpEnv))

    fun configFile(json: String, dir: File = File(System.getProperty("java.io.tmpdir"), "infantry-mcp")): String {
        val sha = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val file = File(dir, "infantry-$sha.json")
        if (!file.isFile || file.readText() != json) {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(json)
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        return file.absolutePath
    }

    /** Pure argv composition. [allowedTools] is ONE comma-joined value (the flag is variadic). */
    fun command(
        executable: String,
        model: String?,
        permissionMode: String?,
        mcpConfigs: List<String>,
        strictMcp: Boolean,
        resumeSessionId: String?,
        effort: String? = null,
        streamInput: Boolean = false,
        allowedTools: List<String> = allowedTools(),
        permissionPromptTool: String? = null,
        settings: String? = null,
        /** G2: names a NEW session (`--session-id`); ignored when [resumeSessionId] is set. */
        sessionId: String? = null,
    ): List<String> = buildList {
        add(executable)
        add("-p")
        if (streamInput) { add("--input-format"); add("stream-json") }
        add("--output-format"); add("stream-json")
        add("--verbose")
        add("--thinking"); add("adaptive"); add("--thinking-display"); add("summarized")
        model?.takeIf { it.isNotBlank() }?.let { add("--model"); add(it) }
        effort?.takeIf { it.isNotBlank() }?.let { add("--effort"); add(it) }
        permissionMode?.takeIf { it.isNotBlank() }?.let { add("--permission-mode"); add(it) }
        allowedTools.takeIf { it.isNotEmpty() }?.let { add("--allowedTools"); add(it.joinToString(",")) }
        permissionPromptTool?.takeIf { it.isNotBlank() }?.let { add("--permission-prompt-tool"); add(it) }
        settings?.takeIf { it.isNotBlank() }?.let { add("--settings"); add(it) }
        mcpConfigs.takeIf { it.isNotEmpty() }?.let {
            add("--mcp-config"); addAll(it)
            if (strictMcp) add("--strict-mcp-config")
        }
        resumeSessionId?.takeIf { it.isNotBlank() }?.let { add("--resume"); add(it) }
            ?: sessionId?.takeIf { it.isNotBlank() }?.let { add("--session-id"); add(it) }
    }

    // ---- interactive mode (CLI-PTY-1, the default since CLI-DEFAULT-1) ---------------------------------------------------

    /**
     * Module setting (`harness.claude.mode`): `interactive` (the default) runs the interactive `claude` in a pseudo-terminal and reads its
     * transcript ([ClaudeTtySession]), as a person runs Claude Code; `headless` (the opt-in) runs `claude -p --input-format stream-json`.
     * Only an explicit `headless` picks headless: unset, blank or any other value = interactive. This is the one place the default lives.
     */
    const val MODE_SETTING = "harness.claude.mode"
    const val MODE_INTERACTIVE = "interactive"
    const val MODE_HEADLESS = "headless"

    fun isInteractive(mode: String?): Boolean = mode?.trim()?.lowercase() != MODE_HEADLESS

    /**
     * The interactive argv: no `-p` and no stream flags. [sessionId] names a NEW session (`--session-id`, so its transcript file is known);
     * [resumeSessionId] continues one. `--mcp-config` (variadic) goes last.
     */
    fun interactiveCommand(
        executable: String,
        model: String?,
        permissionMode: String?,
        mcpConfigs: List<String>,
        strictMcp: Boolean,
        sessionId: String?,
        resumeSessionId: String?,
        effort: String? = null,
        allowedTools: List<String> = allowedTools(),
        settings: String? = null,
    ): List<String> = buildList {
        add(executable)
        resumeSessionId?.takeIf { it.isNotBlank() }?.let { add("--resume"); add(it) }
            ?: sessionId?.takeIf { it.isNotBlank() }?.let { add("--session-id"); add(it) }
        model?.takeIf { it.isNotBlank() }?.let { add("--model"); add(it) }
        effort?.takeIf { it.isNotBlank() }?.let { add("--effort"); add(it) }
        // CLI-PTY-2: without it the interactive transcript stores thinking blocks as signature only (empty text), as the CLI omits them.
        add("--thinking-display"); add("summarized")
        permissionMode?.takeIf { it.isNotBlank() }?.let { add("--permission-mode"); add(it) }
        allowedTools.takeIf { it.isNotEmpty() }?.let { add("--allowedTools"); add(it.joinToString(",")) }
        settings?.takeIf { it.isNotBlank() }?.let { add("--settings"); add(it) }
        mcpConfigs.takeIf { it.isNotEmpty() }?.let { add("--mcp-config"); addAll(it) }
        // Strict even with no config listed: it is what keeps a project .mcp.json (and its approval dialog) out.
        if (strictMcp) add("--strict-mcp-config")
    }

    /** A command hook that appends [event] to [eventsFile]; `echo x>>"file"` means the same in cmd and in a POSIX shell. */
    fun eventHook(event: String, eventsFile: String): JsonObject = JsonObject(
        mapOf(
            "hooks" to JsonArray(listOf(JsonObject(mapOf(
                "type" to JsonPrimitive("command"),
                "command" to JsonPrimitive("echo $event>>\"${eventsFile.replace('\\', '/')}\""),
            )))),
        ),
    )

    /**
     * The interactive session's ONE `--settings` file (claude keeps only the last `--settings`, so the watch hooks of [watchSettings] are
     * merged in, not passed beside it): SessionStart / UserPromptSubmit / Stop append `start` / `submit` / `stop` to [eventsFile];
     * [bypass] adds `skipDangerousModePermissionPrompt`, the documented switch for the warning that bypassPermissions shows at start.
     */
    fun ttySettings(eventsFile: String, watchSettings: String?, bypass: Boolean): String {
        val hooks = LinkedHashMap<String, JsonArray>(TtyJson.hooksOf(watchSettings))
        for ((hook, event) in listOf("SessionStart" to ClaudeTtySession.EV_START, "UserPromptSubmit" to ClaudeTtySession.EV_SUBMIT, "Stop" to ClaudeTtySession.EV_STOP)) {
            hooks[hook] = JsonArray(hooks[hook].orEmpty() + eventHook(event, eventsFile))
        }
        val root = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        if (bypass) root["skipDangerousModePermissionPrompt"] = JsonPrimitive(true)
        root["showThinkingSummaries"] = JsonPrimitive(true) // CLI-PTY-2: the settings twin of --thinking-display summarized
        root["hooks"] = JsonObject(hooks)
        return JsonObject(root).toString()
    }

    // ---- watch (STALL-2): the PreToolUse / PostToolUse http hook, in a settings file ----------------------------------

    const val WATCH_PATH = "/watch/rewrite"
    const val SESSION_HEADER = "X-Infantry-Session"
    const val TOKEN_ENV = "INFANTRY_TOKEN"
    const val SESSION_ID_ENV = "INFANTRY_SESSION_ID"
    const val MAIL_BASE_ENV = "INFANTRY_MAIL_BASE"
    const val WATCH_SETTINGS_FILE = "claude-watch.json"

    /** The `--settings` JSON for a session whose env is [env]; null when its watch is off or it has no daemon URL. */
    fun watchSettings(env: Map<String, String>): String? {
        if (env["INFANTRY_WATCH"] != "on" || env[MAIL_BASE_ENV].isNullOrBlank()) return null
        val url = env.getValue(MAIL_BASE_ENV).trimEnd('/') + "$WATCH_PATH/claude"
        val hook = JsonObject(
            mapOf(
                "type" to JsonPrimitive("http"),
                "url" to JsonPrimitive(url),
                "timeout" to JsonPrimitive(5),
                "onFailure" to JsonPrimitive("continue"),
                "headers" to JsonObject(
                    mapOf(
                        "Authorization" to JsonPrimitive("Bearer \$$TOKEN_ENV"),
                        SESSION_HEADER to JsonPrimitive("\$$SESSION_ID_ENV"),
                    ),
                ),
                "allowedEnvVars" to JsonArray(listOf(JsonPrimitive(TOKEN_ENV), JsonPrimitive(SESSION_ID_ENV))),
            ),
        )
        val entry = JsonObject(mapOf("matcher" to JsonPrimitive("Bash|PowerShell"), "hooks" to JsonArray(listOf(hook))))
        val post = JsonObject(hook + ("url" to JsonPrimitive("$url-post")))
        val postEntry = JsonObject(mapOf("matcher" to JsonPrimitive("Bash|PowerShell"), "hooks" to JsonArray(listOf(post))))
        return JsonObject(mapOf("hooks" to JsonObject(mapOf("PreToolUse" to JsonArray(listOf(entry)), "PostToolUse" to JsonArray(listOf(postEntry)))))).toString()
    }

    // ---- capabilities, commands, models --------------------------------------------------------------------------------

    const val CONTEXT_LIMIT_ENV = "CLAUDE_CODE_AUTO_COMPACT_WINDOW"

    val CONTEXT_LIMIT = ContextLimitCapability(
        ContextLimitSupport.SUPPORTED,
        "Claude Code auto-compacts at the limit ($CONTEXT_LIMIT_ENV); a value under its minimum is raised to the minimum",
    )

    fun contextLimitEnv(limit: Int?): Map<String, String> = limit?.takeIf { it > 0 }?.let { mapOf(CONTEXT_LIMIT_ENV to it.toString()) }.orEmpty()

    val EFFORT = EffortCapability(EffortSupport.SUPPORTED, "claude --effort <level> (low, medium, high, xhigh, max)")

    val EFFORT_LEVELS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

    val COMMANDS: List<HarnessCommandSpec> = listOf(
        HarnessCommandSpec("model", "Switch the model for the next turn", HarnessCommandAction.SET_MODEL, argument = "<model>"),
        HarnessCommandSpec("effort", "Set the reasoning effort for the next turn", HarnessCommandAction.SET_EFFORT, argument = "<level>", values = EFFORT_LEVELS),
        HarnessCommandSpec("compact", "Compact the conversation (the harness's own compaction)", HarnessCommandAction.NATIVE, native = "/compact"),
    )

    /** Aliases first (they track the newest model of each family), then pinned full ids. */
    val MODELS: List<String> = listOf(
        "opus", "sonnet", "haiku", "opus[1m]", "sonnet[1m]", "opusplan",
        "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1",
    )

    fun models(): List<HarnessModels> = listOf(HarnessModels("claude", "Claude", MODELS.distinct()))
}
