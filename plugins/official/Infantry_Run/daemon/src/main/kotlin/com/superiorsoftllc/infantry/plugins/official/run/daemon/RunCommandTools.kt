// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * RUN-CMD-TOOL-1: the Run tab's saved commands as daemon tools (MCP, `infantry run-cmd`, Lua `ih.call`). Each tool goes through
 * the same [CommandStore] / [RunManager] the frames use, so validation and refusals are identical; the host gates each tool by the
 * action module.json declares for it (commands.read, commands.save, runs.start, runs.stop).
 *
 * Project scope (RUN-SCOPE-1): a saved command belongs to one project and its cwd is relative to that project's folder. Every call
 * acts in a project: its `project` argument, else the caller's own. List shows that project's commands and the unassigned ones;
 * delete and start refuse another project's command. A `project` other than the caller's own is refused unless
 * `allowCrossProject` is true (as for cross-project mail).
 */
class RunCommandTools(
    private val store: CommandStore,
    private val manager: RunManager,
    private val broadcastList: () -> Unit,
    private val emit: (Envelope) -> Unit,
    /** SECRETS-2b: the secret names a project's run can use, with the scope each resolves to (names only). */
    private val secretNames: (String?) -> Map<String, String> = { emptyMap() },
    /** SECRET-RENDER-1: the renderer and a project's folder (relative template paths are resolved against it). */
    private val renderer: SecretRenderer? = null,
    private val rootOf: (String?) -> java.io.File? = { null },
) {
    fun all(): List<DaemonTool> = listOf(
        tool("infantry_run_commands", "List a project's saved Run-tab commands (id, name, cwd, argv, project) and the unassigned ones; default: your project.",
            listOf("project" to "project id (default: yours)", "allowCrossProject" to "boolean"), listOf(), true) { list(it) },
        tool("infantry_run_command_save", "Add or edit a project's saved command. Give `argv` (array) or `command` (a shell line, run through the system shell).",
            listOf("id" to "command id (upsert key)", "name" to "display name", "command" to "shell command line",
                "cwd" to "working dir relative to the project's folder (. or blank = the folder itself; an absolute path must be inside it)",
                "project" to "project id the command is for", "allowCrossProject" to "boolean: allow a project other than the caller's",
                "renders" to "array of template=out secret-template renders (relative to the command folder) done before each run; outputs are deleted when it ends"), listOf("id"), false) { save(it) },
        tool("infantry_run_command_delete", "Delete a saved command by id.", listOf("id" to "command id", "project" to "project id", "allowCrossProject" to "boolean"), listOf("id"), false) { delete(it) },
        tool("infantry_run_start", "Start a saved command; returns the run id.", listOf("id" to "command id", "project" to "project id", "allowCrossProject" to "boolean"), listOf("id"), false) { start(it) },
        tool("infantry_run_secrets", "List the secret names a project's saved commands can use and the scope each resolves to (project first, then app). Names only, never a value; secrets are created in Configuration by the owner.",
            listOf("project" to "project id (default: yours)", "scope" to "project | app | all (default: all)", "allowCrossProject" to "boolean"), listOf(), true) { secretList(it) },
        tool("infantry_secret_render", "Render a text file (.env, .json, .yaml, any text) replacing {{secret:NAME}} placeholders with the saved secret values, into an owner-only output file. Returns names and the output path only, never a value. `check` reports found/missing names and writes nothing. A missing secret fails and writes nothing unless allowMissing.",
            listOf("template" to "the template file (relative = inside the project folder)", "out" to "output file (required unless check); never the template itself",
                "project" to "project id for secret scope (default: yours)", "allowMissing" to "boolean: leave unknown placeholders in", "check" to "boolean: report names only, write nothing",
                "allowCrossProject" to "boolean"), listOf("template"), false) { render(it) },
        tool("infantry_run_stop", "Stop a run by run id (force=true kills at once).", listOf("runId" to "run id", "force" to "boolean"), listOf("runId"), false) { stop(it) },
    )

    /** RUN-SCOPE-1: the target project's commands and the unassigned ones; a caller with no project sees them all. */
    private fun list(c: ToolCall): ToolResult {
        crossProjectRefusal(c)?.let { return err(it) }
        return ToolResult(store.refreshUnrooted().specsJson(target(c)))
    }

    /** SECRETS-2b: names and scopes only; no value ever reaches a tool. */
    private fun secretList(c: ToolCall): ToolResult {
        crossProjectRefusal(c)?.let { return err(it) }
        val scope = str(c.arguments, "scope")?.lowercase() ?: "all"
        if (scope !in setOf("project", "app", "all")) return err("scope is project, app or all")
        val project = target(c)
        val rows = secretNames(project).filter { scope == "all" || it.value == scope }.toSortedMap()
        if (rows.isEmpty()) return ToolResult("no secrets" + (project?.let { " for project '$it'" } ?: "") + if (scope == "all") "" else " in scope $scope")
        return ToolResult(rows.entries.joinToString("\n") { (n, s) -> "$n  ($s)" })
    }

    /** SECRET-RENDER-1: names and the written path only; the value never leaves the renderer. */
    private fun render(c: ToolCall): ToolResult {
        crossProjectRefusal(c)?.let { return err(it) }
        val r = renderer ?: return err("secret rendering is not available")
        val project = target(c)
        val root = rootOf(project)
        fun file(p: String): java.io.File = java.io.File(p).let { if (it.isAbsolute || root == null) it else java.io.File(root, p) }
        val template = file(str(c.arguments, "template") ?: return err("template is required"))
        val check = (c.arguments["check"] as? JsonPrimitive)?.booleanOrNull == true
        val allowMissing = (c.arguments["allowMissing"] as? JsonPrimitive)?.booleanOrNull == true
        val res = r.render(template, str(c.arguments, "out")?.let(::file), project, allowMissing, check)
        return if (res.ok) ToolResult(res.summary(check)) else err(res.summary(check))
    }

    /** The project a call acts in: its `project` argument, else the caller's own. */
    private fun target(c: ToolCall): String? = str(c.arguments, "project") ?: c.clientProjectId

    private fun save(c: ToolCall): ToolResult {
        crossProjectRefusal(c)?.let { return err(it) }
        val a = c.arguments
        val id = str(a, "id") ?: return err("id is required")
        val argv = (a["argv"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: str(a, "command")?.let { shellArgv(it) }
            ?: return err("argv (array) or command (string) is required")
        val data = buildJsonObject {
            put("id", id)
            str(a, "name")?.let { put("name", it) }
            str(a, "cwd")?.let { put("cwd", it) }
            put("argv", JsonArray(argv.map { JsonPrimitive(it) }))
            (a["renders"] as? JsonArray)?.let { put("renders", it) }
        }
        val refusal = store.save(Json.encodeToString(JsonObject.serializer(), data), manager::unlessRunning, str(a, "project") ?: c.clientProjectId)
        if (refusal != null) return err("infantry_run_command_save refused: $refusal")
        broadcastList()
        return ToolResult("saved command '$id'")
    }

    private fun delete(c: ToolCall): ToolResult {
        crossProjectRefusal(c)?.let { return err(it) }
        val id = str(c.arguments, "id") ?: return err("id is required")
        val refusal = store.delete(Json.encodeToString(JsonObject.serializer(), buildJsonObject { put("id", id) }), manager::unlessRunning, target(c))
        if (refusal != null) return err("infantry_run_command_delete refused: $refusal")
        broadcastList()
        return ToolResult("deleted command '$id'")
    }

    private fun start(c: ToolCall): ToolResult {
        crossProjectRefusal(c)?.let { return err(it) }
        val id = str(c.arguments, "id") ?: return err("id is required")
        store.refreshUnrooted()
        val started = manager.start(Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("commandId", id)
            target(c)?.let { put("project", it) }
        }))
        if (started.reply.type == Envelope.ERROR) return err(started.reply.data)
        emit(started.reply)
        started.launch()
        val runId = (runCatching { Json.parseToJsonElement(started.reply.data) }.getOrNull() as? JsonObject)?.get("runId")
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
        return ToolResult("started run ${runId ?: "?"}")
    }

    private fun stop(c: ToolCall): ToolResult {
        val runId = str(c.arguments, "runId") ?: return err("runId is required")
        val force = (c.arguments["force"] as? JsonPrimitive)?.booleanOrNull == true
        // HARNESS-GAP G17: a run belongs to its command's project; another project's run is refused like any other tool's.
        foreignRefusal(manager.projectOfRun(runId), c)?.let { return err("$it (run $runId)") }
        val reply = manager.stop(if (force) "$runId force" else runId)
        return if (reply != null && reply.type == Envelope.ERROR) err(reply.data) else ToolResult("stop requested for run $runId")
    }

    private fun crossProjectRefusal(c: ToolCall): String? = foreignRefusal(str(c.arguments, "project"), c)

    /** A refusal when [wanted] (a project id) is not the caller's own and the call does not set allowCrossProject; null otherwise. */
    private fun foreignRefusal(wanted: String?, c: ToolCall): String? {
        wanted ?: return null
        val own = c.clientProjectId ?: return null
        if (wanted == own) return null
        if ((c.arguments["allowCrossProject"] as? JsonPrimitive)?.booleanOrNull == true) return null
        return "refused: project '$wanted' is not the caller's project '$own'; pass allowCrossProject=true to act across projects"
    }

    private fun err(text: String) = ToolResult(text, isError = true)
    private fun str(a: JsonObject, k: String) = (a[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun shellArgv(line: String): List<String> =
        if (System.getProperty("os.name").orEmpty().lowercase().contains("win")) listOf("cmd", "/c", line) else listOf("sh", "-c", line)

    private fun tool(n: String, d: String, p: List<Pair<String, String>>, required: List<String>, readOnly: Boolean, body: (ToolCall) -> ToolResult) = object : DaemonTool {
        override val name = n
        override val description = d
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                p.forEach { (k, desc) -> put(k, buildJsonObject { put("type", if (k == "force" || k == "allowCrossProject") "boolean" else "string"); put("description", desc) }) }
            })
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
        override val annotations = if (readOnly) buildJsonObject { put("readOnlyHint", true) } else JsonObject(emptyMap())
        override fun call(call: ToolCall) = body(call)
    }
}
