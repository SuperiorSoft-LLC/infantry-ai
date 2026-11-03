// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.mailbox.WatchMeter
import com.superiorsoftllc.infantry.runner.WatchRewrite
import io.ktor.http.ContentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * STALL-2 (ticket a46744ec) item 1: the per-harness hooks that FORCE a long or backgrounded shell command through the
 * watched runner, path-named programs (`./gradlew`, `gradlew.bat`) included, which the PATH shims cannot see. Every hook
 * asks ONE endpoint, `POST /watch/rewrite/{harness}` (bearer like every route; the session id in [SESSION_HEADER]), so
 * the rules live once ([WatchRewrite]) and no process is spawned per shell call:
 *
 *  - claude: a PreToolUse `http` hook in a settings file passed with `--settings` (written by the Infantry_Claude module's watch()); Claude Code 2.1.312 applies a hook's
 *    `updatedInput` with no `permissionDecision` and then runs its normal permission check on the rewritten input.
 *  - opencode: the plugin `infantry-watch.js` (`tool.execute.before`, mutates `output.args.command`), shipped by the opencode harness module (Infantry_OpenCode) and named in
 *    the serve's inline config `plugin` list by its config merge.
 *  - pi: the extension `infantry-watch.ts` (`tool_call`, mutates `event.input.command`), shipped by the pi harness module (Infantry_Pi) and loaded with `-e` through its `watch()` contribution.
 *
 * PERMISSIONS: the rewritten command starts with `infantry`, which every harness allows in every mode (BUG-PERM-5). So a
 * rewrite is only made where the original would have run without asking anyway: claude in `bypassPermissions`, opencode
 * in `allowAll`; pi has no permission system. In any other mode the command is left alone (the process-tree watch and the
 * stall detector still cover it): a hook must never widen what a session may run.
 */
object WatchHooks {
    const val PATH = "/watch/rewrite"
    const val SESSION_HEADER = "X-Infantry-Session"
    private val json = Json { ignoreUnknownKeys = true }

    /** BG-NATIVE-1: harnesses with their own background tasks; their `run_in_background` is not rewritten in native mode. */
    val NATIVE_BG_HARNESSES = setOf("claude")

    /** BG-NATIVE-1: `limits.watch.bgNative`, set by the daemon (default native). */
    @Volatile var nativeBackground: () -> Boolean = { false }

    internal fun on(env: Map<String, String>) = env["INFANTRY_WATCH"] == "on" && !env[AccessPolicy.MAIL_BASE_ENV].isNullOrBlank()

    /** Writes [resource] into [dir] as [name] (only when missing or different) and returns the file. */
    fun install(dir: File, resource: String, name: String): File {
        val text = requireNotNull(WatchHooks::class.java.getResource(resource)) { "missing resource $resource" }.readText()
        val file = File(dir, name)
        if (!file.isFile || file.readText() != text) {
            dir.mkdirs()
            file.writeText(text)
        }
        return file
    }

    /**
     * The answer for one hook call. [harness] = `claude` (Claude Code's PreToolUse input, answered in its hook output
     * shape) or `opencode` / `pi` (`{tool, command, background}` in, `{command}` out). `{}` = run the command unchanged.
     */
    /**
     * The PostToolUse side of the claude hook. Claude Code runs every PreToolUse hook of every settings source and applies
     * ONE `updatedInput` when several rewrite the same call (the STALL-2 live run: the owner's own `rtk hook claude`
     * replaced ours for `./gradlew`, ours won for `npm`). The order is not ours to set, so the miss is made visible: the
     * command that ran is compared with our rewrite of that `tool_use_id`, and a mismatch is counted, logged and told to
     * the agent (the job ran unwatched; the process-tree watch and the stall detector still cover it).
     */
    const val CLAUDE_POST = "claude-post"

    /** Harness ids whose watch hook runs whatever the approval mode; registered by [CoreHarnessContributions]. */
    @Volatile var approvalExempt: Set<String> = emptySet()

    /** tool_use_id -> our rewritten command, for [CLAUDE_POST]; bounded (a call whose post never comes ages out). */
    private val rewritten = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }

    fun decide(harness: String, body: String, approval: ToolApproval?, launcher: String?, windows: Boolean = isWindows()): Decision {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return Decision.none
        if (harness == CLAUDE_POST) {
            val id = root["tool_use_id"]?.str() ?: return Decision.none
            val ours = synchronized(rewritten) { rewritten.remove(id) } ?: return Decision.none
            val ran = (root["tool_input"] as? JsonObject)?.get("command")?.str() ?: return Decision.none
            if (ran == ours) return Decision.none
            val note = "This command ran UNWATCHED: another PreToolUse hook replaced Infantry AI's watched-runner rewrite " +
                "(it ran: ${ran.take(200)}). Its end is not mailed with an exit code; if it is long or backgrounded, rerun it as: " +
                "infantry run-watched -- <cmd>"
            val out = JsonObject(mapOf("hookSpecificOutput" to JsonObject(mapOf("hookEventName" to JsonPrimitive("PostToolUse"), "additionalContext" to JsonPrimitive(note)))))
            return Decision(out.toString(), null, overriddenBy = ran)
        }
        return if (harness == "claude") {
            val tool = root["tool_name"]?.str() ?: return Decision.none
            val shell = shellOf(tool) ?: return Decision.none
            // The session's own mode, as Claude reports it: only bypassPermissions ran the original without asking.
            if (root["permission_mode"]?.str() != "bypassPermissions") return Decision.none
            val input = root["tool_input"] as? JsonObject ?: return Decision.none
            val command = input["command"]?.str() ?: return Decision.none
            val bg = (input["run_in_background"] as? JsonPrimitive)?.booleanOrNull == true
            if (bg && harness in NATIVE_BG_HARNESSES && nativeBackground()) return Decision.none
            val r = WatchRewrite.rewrite(WatchRewrite.Request(command, shell, bg, "claude", launcher, windows)) ?: return Decision.none
            root["tool_use_id"]?.str()?.let { id -> synchronized(rewritten) { rewritten[id] = r.command } }
            val updated = JsonObject(input + ("command" to JsonPrimitive(r.command)) + (if (bg) mapOf("run_in_background" to JsonPrimitive(false)) else emptyMap()))
            val out = JsonObject(
                mapOf(
                    "hookSpecificOutput" to JsonObject(
                        mapOf(
                            "hookEventName" to JsonPrimitive("PreToolUse"),
                            "updatedInput" to updated,
                            "additionalContext" to JsonPrimitive(context(r)),
                        ),
                    ),
                ),
            )
            Decision(out.toString(), r)
        } else {
            val shell = shellOf(root["tool"]?.str() ?: return Decision.none) ?: return Decision.none
            if (harness !in approvalExempt && approval != ToolApproval.ALLOW_ALL) return Decision.none
            val command = root["command"]?.str() ?: return Decision.none
            val bg = (root["background"] as? JsonPrimitive)?.booleanOrNull == true
            val r = WatchRewrite.rewrite(WatchRewrite.Request(command, shell, bg, harness, launcher, windows)) ?: return Decision.none
            Decision(JsonObject(mapOf("command" to JsonPrimitive(r.command), "detached" to JsonPrimitive(r.detached))).toString(), r)
        }
    }

    /** [overriddenBy]: [CLAUDE_POST] found another hook's command ran instead of our rewrite. */
    data class Decision(val body: String, val rewrite: WatchRewrite.Result?, val overriddenBy: String? = null) {
        companion object { val none = Decision("{}", null) }
    }

    private fun context(r: WatchRewrite.Result) =
        if (r.detached) {
            "Infantry AI ran this backgrounded command DETACHED under its watched runner: the daemon adds a one-line finished note (id, exit code, log path) to your next turn when it ends. " +
                "Read the log yourself if you need more. End your turn now; do not sleep or poll."
        } else {
            "Infantry AI ran this command through its watched runner (same output and exit code, plus a log and an END line). " +
                "Opt out only with: infantry unwatched --reason \"<why>\" -- <cmd...>"
        }

    private fun shellOf(tool: String) = when (tool.lowercase()) {
        "bash" -> WatchRewrite.Shell.BASH
        "powershell" -> WatchRewrite.Shell.POWERSHELL
        else -> null
    }

    private fun kotlinx.serialization.json.JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    /** The `infantry` launcher a rewritten command names: the installed script, or null (= `infantry` from PATH). */
    fun launcher(binDir: File?): String? =
        com.superiorsoftllc.infantry.runner.WatchShims.launcher(binDir).singleOrNull()
}

/** `POST /watch/rewrite/{harness}`: one hook call; [approvalOf] = the session's Tool approval (null = unknown session). */
fun Route.watchHookRoutes(approvalOf: (String) -> ToolApproval?, launcher: () -> String?) {
    post("${WatchHooks.PATH}/{harness}") {
        val harness = call.parameters["harness"].orEmpty()
        val session = (call.request.headers[WatchHooks.SESSION_HEADER] ?: call.request.headers["X-Agenthq-Session"])?.trim()?.takeIf { it.isNotEmpty() }
        val body = runCatching { call.receiveText() }.getOrDefault("")
        val d = runCatching {
            WatchHooks.decide(harness, body, session?.let(approvalOf), launcher())
        }.getOrElse {
            System.err.println("[infantry] watch hook ($harness) failed: ${it.message}")
            WatchHooks.Decision.none
        }
        d.rewrite?.let { r ->
            WatchMeter.hookRewrite()
            println("[infantry] watch hook ($harness) session ${session ?: "?"}: ${if (r.detached) "detached" else "attached"} run of ${r.matched}")
        }
        d.overriddenBy?.let { ran ->
            WatchMeter.hookOverride()
            println("[infantry] watch hook (claude) session ${session ?: "?"}: OVERRIDDEN by another PreToolUse hook, ran unwatched: ${ran.take(120)}")
        }
        call.respondText(d.body, ContentType.Application.Json)
    }
}
