package com.superiorsoftllc.agenthq.experiments.pitoolbridge

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.AgentHqMcp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * EXPERIMENT (ticket c62a975a), OFF BY DEFAULT: attach the agenthq MCP to pi, so a pi worker can reach mail and the board
 * through MCP tools instead of the `agenthq` CLI over bash. Read the README in this package before touching it: the whole
 * point is that it can be switched off with one setting and deleted with one package.
 *
 * THE SWITCH ([enabled]), read at every session START, never cached: the `AGENTHQ_EXPERIMENT_PI_TOOL_BRIDGE` environment
 * variable (`on`/`off`, session env first, then the daemon's) beats the project setting `experiments.piToolBridge`
 * (`project_config`). Neither set = off. A running session is never touched: [launchArgs] is asked once per process start,
 * and the pi session fingerprint ignores it.
 *
 * HOW pi GETS THE SERVER. pi has no `--mcp-config`; it reads `<cwd>/.pi/mcp.json` (project trust required, so the launch
 * adds `--approve`). The file names the agenthq server with exposure `deferred` (tools load on demand through
 * `tool_search`, about +350 tokens at start as the spike measured), the [DIRECT] read/mail tools `direct`, and the tools
 * a role may not use `hidden` (the role scope; [hiddenFor]).
 *
 * OFF = TODAY. When off, nothing is attached. The one thing the off path does is delete a `.pi/mcp.json` THIS package wrote
 * earlier (it carries [MARKER]); a file the user wrote is never touched.
 */
object PiToolBridge {
    /** The `Project.experiments` key and the name in the UI. */
    const val KEY = "piToolBridge"

    /** The override: `on` or `off`, beats the project setting. */
    const val ENV = "AGENTHQ_EXPERIMENT_PI_TOOL_BRIDGE"

    /** Written into the generated file, so the off path only ever deletes its own. */
    const val MARKER = "_agenthqExperiment"

    /** The tag the stats reader puts on a session whose pi record shows the bridge attached. */
    const val TAG = "experiment:piToolBridge"

    /** The flat read/mail set every role sees without searching. Simple string arguments only (spike mail, point 5d). */
    val DIRECT: List<String> = listOf(
        "agenthq_inbox", "agenthq_read", "agenthq_message", "agenthq_ticket_get", "agenthq_board", "agenthq_progress",
        "agenthq_ticket_files", "agenthq_ticket_related_files",
    )

    /** Big nested schemas or authority a cheap model should not hold (spike mail, point 5d). */
    private val MUTATING: List<String> = listOf(
        "agenthq_ticket_create", "agenthq_ticket_update", "agenthq_ticket_move", "agenthq_ticket_delete", "agenthq_ticket_start",
        "agenthq_project_config_set", "agenthq_role_create", "agenthq_role_update", "agenthq_role_delete",
        "agenthq_harness_install", "agenthq_pipeline_record", "agenthq_epic_base",
    )
    private val DESTRUCTIVE: List<String> = listOf(
        "agenthq_spawn", "agenthq_kill", "agenthq_prompt", "agenthq_session_remove", "agenthq_session_restart", "agenthq_processes_reap",
    )

    /** Roles that run the board: they keep the ticket tools, nothing destructive. */
    private val LEAD_ROLES = setOf("pm", "architect")

    /** The tools [role] may not reach at all (`hidden`). */
    fun hiddenFor(role: String?): List<String> =
        if (role?.trim()?.lowercase() in LEAD_ROLES) DESTRUCTIVE else DESTRUCTIVE + MUTATING

    /** The project setting, installed at the one registration point; default: nothing is on. */
    @Volatile var projectSwitch: (projectId: String?) -> Boolean = { false }

    fun enabled(sessionEnv: Map<String, String>, processEnv: Map<String, String> = System.getenv()): Boolean {
        val override = (sessionEnv[ENV] ?: processEnv[ENV])?.trim()?.lowercase()
        return when (override) {
            "on", "true" -> true
            "off", "false" -> false
            else -> projectSwitch(sessionEnv[AccessPolicy.PROJECT_ID_ENV])
        }
    }

    /** The `.pi/mcp.json` text for a session with env [sessionEnv]. */
    fun mcpJson(sessionEnv: Map<String, String>): String {
        val launch = AgentHqMcp.launch(sessionEnv)
        val toolExposure = linkedMapOf<String, JsonPrimitive>()
        hiddenFor(sessionEnv[AccessPolicy.ROLE_ENV]).forEach { toolExposure[it] = JsonPrimitive("hidden") }
        DIRECT.forEach { toolExposure[it] = JsonPrimitive("direct") }
        val server = JsonObject(mapOf(
            "command" to JsonPrimitive(launch.command),
            "args" to JsonArray(launch.args.map(::JsonPrimitive)),
            "env" to JsonObject(launch.env.mapValues { JsonPrimitive(it.value) }),
            "exposure" to JsonPrimitive("deferred"),
            "toolExposure" to JsonObject(toolExposure),
        ))
        val root = JsonObject(mapOf(
            MARKER to JsonPrimitive(true),
            "mcpServers" to JsonObject(mapOf(AgentHqMcp.SERVER_NAME to server)),
        ))
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * The extra pi argv for a session start in [workingDir] with env [sessionEnv]. Empty when the switch is off (after
     * removing a stale file of ours) or when there is no working directory to put the file in.
     */
    fun launchArgs(workingDir: File?, sessionEnv: Map<String, String>): List<String> {
        val file = workingDir?.let { File(it, ".pi/mcp.json") }
        if (!enabled(sessionEnv)) {
            if (file != null && file.isFile && runCatching { file.readText().contains(MARKER) }.getOrDefault(false)) file.delete()
            return emptyList()
        }
        if (file == null) return emptyList()
        // A file the user wrote is theirs: no bridge for this start rather than overwriting it.
        if (file.isFile && !file.readText().contains(MARKER)) return emptyList()
        val text = mcpJson(sessionEnv)
        if (!file.isFile || file.readText() != text) {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        return listOf("--approve")
    }

    /** Whether one line of a pi session record (its system message) shows the agenthq server attached. */
    fun tagOf(line: JsonObject): String? {
        val message = line["message"] as? JsonObject ?: return null
        if ((message["role"] as? JsonPrimitive)?.content != "system") return null
        val text = message.toString()
        return if (text.contains("mcp__${AgentHqMcp.SERVER_NAME}__") || text.contains("mcp_servers")) TAG else null
    }
}
