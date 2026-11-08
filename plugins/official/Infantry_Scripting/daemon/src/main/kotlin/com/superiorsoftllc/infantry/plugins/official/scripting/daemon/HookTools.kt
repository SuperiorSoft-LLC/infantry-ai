// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * SCRIPT-5: `infantry_script_hook`, the one tool behind `infantry script hook list|add|remove`. Hooks are data in the project's
 * `.infantry/scripts/hooks.json` ([HookStore]). Open to every role (D18): the caller's identity is recorded as the hook's owner, never tested.
 */
class HookTools(
    private val store: HookStore, private val scripts: ScriptStore, private val minEverySec: () -> Long = { 10L },
    /** SCRIPT-ARSENAL-2: the refusal text when the caller may not write global hooks (same rule as global scripts), else null. */
    private val globalDenied: (ToolCall) -> String? = { null },
) {
    val tool: DaemonTool = object : DaemonTool {
        override val name = NAME
        override val description = "List, add or remove the project's script hooks (data in .infantry/scripts/hooks.json). A hook runs a stored script, daemon-side, with no model: " +
            "on a named event (on='ticket.commented', 'merge.done', 'ticket.*') or when a ticket enters or leaves a lane (on='lane enter done', 'lane exit in_progress'). The script gets the hook's args " +
            "plus the event payload (ticketId, from, to, branch, tipSha, workerSession, projectId...) as input.args, and runs as you. A loop guard (depth + rate, plugin settings) stops runaway chains; failures are commented on the ticket or mailed to you. Open to every role."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"action":{"type":"string","description":"list | add | remove"},"on":{"type":"string","description":"add: 'every <N>s' (timer, floor from setting scripting.hooks.minEverySec; input.args gets tick, at, hookId), 'exec.tick' (each live background exec run: id, script, session, elapsedMs, stdoutTail, stderrTail, bytesSinceLast), 'lane enter <lane>' or 'lane exit <lane>' (todo, in_progress, pending, done, a user lane) or a named event like ticket.commented"},"script":{"type":"string","description":"add: stored script name (see infantry_script_run)"},"args":{"type":"string","description":"add: JSON object of fixed args merged under the event payload (default {})"},"id":{"type":"string","description":"add: hook id (default from script + on); remove: the id"},"to":{"type":"string","description":"add: on=mail.incoming: the ONE session id whose incoming mail the hook handles (default: your own session). on='every <N>s' or 'exec.tick': optional session the script acts for (infantry_progress from the script lands on its card); the hook goes with that session"},"disabled":{"type":"boolean","description":"add: store it switched off"},"global":{"type":"boolean","description":"add: also run for events that name no project (daemon.started, a project-less emit). A hook never runs for another project's events"},"scope":{"type":"string","description":"add/remove: 'global' = an arsenal hook that runs for EVERY project's events as that project (writers are the setting scripting.global.writeRoles); a project hook of the same id shadows it. Default project"},"project":{"type":"string","description":"Project id (default: yours)"}},"required":["action"]}""",
        ).jsonObject
        override fun call(call: ToolCall): ToolResult = handle(call)
    }

    fun handle(call: ToolCall): ToolResult {
        val a = call.arguments
        fun s(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val project = s("project") ?: call.clientProjectId ?: return refuse("needs a project (pass project, or run it from a project session)")
        if (s("scope")?.lowercase() == "global" && s("action")?.lowercase() in setOf("add", "remove")) {
            globalDenied(call)?.let { return refuse(it) }
            return try {
                if (s("action")?.lowercase() == "remove") {
                    val id = s("id") ?: return refuse("remove needs id")
                    if (store.removeArsenal(id)) ToolResult("removed global hook $id") else refuse("no global hook '$id'")
                } else addArsenal(call, s("on"), s("script"), s("id"), a["args"], (a["disabled"] as? JsonPrimitive)?.booleanOrNull == true)
            } catch (e: IllegalArgumentException) { refuse(e.message.orEmpty()) } catch (e: IllegalStateException) { refuse(e.message.orEmpty()) }
        }
        return try {
            when (val action = s("action")?.lowercase()) {
                "list" -> list(project)
                "add" -> add(call, project, s("on"), s("script"), s("id"), s("to"), a["args"], (a["disabled"] as? JsonPrimitive)?.booleanOrNull == true, (a["global"] as? JsonPrimitive)?.booleanOrNull == true)
                "remove" -> remove(project, s("id") ?: return refuse("remove needs id"))
                else -> refuse("action must be list, add or remove (got '${action.orEmpty()}')")
            }
        } catch (e: IllegalArgumentException) {
            refuse(e.message.orEmpty())
        } catch (e: IllegalStateException) {
            refuse(e.message.orEmpty())
        }
    }

    private fun list(project: String): ToolResult {
        val hooks = store.list(project)
        val err = store.error(project)
        val text = buildString {
            if (hooks.isEmpty()) append("no hooks in project $project") else append("${hooks.size} hook(s) in project $project:\n").append(hooks.joinToString("\n") { "  " + it.line() })
            if (err != null) append("\nWARNING: ${HookStore.PATH} does not parse, so NO hook is active: $err")
        }
        return ToolResult(text)
    }

    private fun add(call: ToolCall, project: String, on: String?, script: String?, id: String?, to: String?, rawArgs: Any?, disabled: Boolean, global: Boolean = false): ToolResult {
        val parsedOn = HookOn.parse(on ?: return refuse("add needs on (e.g. 'lane enter done' or 'ticket.commented')"))
        val name = script ?: return refuse("add needs script")
        scripts.read(project, name) ?: return refuse("no script '$name' in project $project (looked in .infantry/scripts/); have: ${runCatching { scripts.names(project) }.getOrDefault(emptyList()).joinToString().ifBlank { "none" }}")
        val args = when (rawArgs) {
            null -> JsonObject(emptyMap())
            is JsonObject -> rawArgs
            is JsonPrimitive -> if (rawArgs.contentOrNull.isNullOrBlank()) JsonObject(emptyMap()) else runCatching { Json.parseToJsonElement(rawArgs.content) as? JsonObject }.getOrNull() ?: return refuse("args must be a JSON object")
            else -> return refuse("args must be a JSON object")
        }
        val isMail = parsedOn is HookOn.Named && parsedOn.pattern == ScriptHooks.INCOMING_EVENT
        val isTimer = parsedOn is HookOn.Every || (parsedOn is HookOn.Named && parsedOn.pattern == HookOn.EXEC_TICK)
        if (parsedOn is HookOn.Every && parsedOn.seconds < minEverySec()) return refuse("every ${parsedOn.seconds}s is below the minimum ${minEverySec()}s (setting scripting.hooks.minEverySec)")
        if (!isMail && !isTimer && to != null) return refuse("'to' only applies to on=mail.incoming, every <N>s and exec.tick")
        val target = if (isMail) (to ?: call.clientSessionId ?: return refuse("a mail.incoming hook attaches to ONE session: pass to=<session id> (no role-wide or project-wide mail hooks)")) else if (isTimer) to else null
        if (target != null && isMail) store.list(project).firstOrNull { it.on == parsedOn && it.to == target && it.script == name }
            ?.let { return refuse("session $target already has script '$name' on mail.incoming (hook ${it.id}); one hook per (session, script)") }
        val hookId = id ?: defaultId(name, parsedOn).let { if (target != null) "$it-${target.take(12)}".take(64).trim('-', '.', '_') else it }
        require(HookStore.validId(hookId)) { "bad hook id '$hookId' (letters, digits, '_', '-', '.'; at most 64)" }
        val byOwner = call.caller.owner && call.clientSessionId == null
        store.add(project, Hook(hookId, parsedOn, name, args, !disabled, call.clientSessionId, call.clientRole, byOwner, target, global))
        return ToolResult("added hook $hookId: on $parsedOn -> $name${if (global) " [global]" else ""}${if (disabled) " [disabled]" else ""}")
    }

    private fun addArsenal(call: ToolCall, on: String?, script: String?, id: String?, rawArgs: Any?, disabled: Boolean): ToolResult {
        val parsedOn = HookOn.parse(on ?: return refuse("add needs on"))
        val name = script ?: return refuse("add needs script")
        if (scripts.global?.read(name) == null) return refuse("no global script '$name' (a global hook names a script of the Script Arsenal; save it with scope=global)")
        if (parsedOn is HookOn.Every && parsedOn.seconds < minEverySec()) return refuse("every ${parsedOn.seconds}s is below the minimum ${minEverySec()}s (setting scripting.hooks.minEverySec)")
        if (parsedOn is HookOn.Named && parsedOn.pattern == ScriptHooks.INCOMING_EVENT) return refuse("a mail.incoming hook attaches to one session; it cannot be global")
        val args = when (rawArgs) {
            null -> JsonObject(emptyMap())
            is JsonObject -> rawArgs
            is JsonPrimitive -> if (rawArgs.contentOrNull.isNullOrBlank()) JsonObject(emptyMap()) else runCatching { Json.parseToJsonElement(rawArgs.content) as? JsonObject }.getOrNull() ?: return refuse("args must be a JSON object")
            else -> return refuse("args must be a JSON object")
        }
        val hookId = id ?: defaultId(name, parsedOn)
        require(HookStore.validId(hookId)) { "bad hook id '$hookId'" }
        val byOwner = call.caller.owner && call.clientSessionId == null
        store.addArsenal(Hook(hookId, parsedOn, name, args, !disabled, call.clientSessionId, call.clientRole, byOwner))
        return ToolResult("added global hook $hookId: on $parsedOn -> $name (runs for every project)${if (disabled) " [disabled]" else ""}")
    }

    private fun remove(project: String, id: String): ToolResult =
        if (store.remove(project, id)) ToolResult("removed hook $id") else refuse("no hook '$id' in project $project")

    private fun defaultId(script: String, on: HookOn): String =
        "$script-${on.toString().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')}".take(64).trim('-', '.', '_')

    private fun refuse(why: String) = ToolResult("$NAME refused: $why", isError = true)

    companion object { const val NAME = "infantry_script_hook" }
}
