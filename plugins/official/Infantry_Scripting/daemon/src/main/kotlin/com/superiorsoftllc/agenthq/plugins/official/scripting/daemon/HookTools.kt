package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * SCRIPT-5: `agenthq_script_hook`, the one tool behind `agenthq script hook list|add|remove`. Hooks are data in the project's
 * `.agenthq/scripts/hooks.json` ([HookStore]). Open to every role (D18): the caller's identity is recorded as the hook's owner, never tested.
 */
class HookTools(private val store: HookStore, private val scripts: ScriptStore) {
    val tool: DaemonTool = object : DaemonTool {
        override val name = NAME
        override val description = "List, add or remove the project's script hooks (data in .agenthq/scripts/hooks.json). A hook runs a stored script, daemon-side, with no model: " +
            "on a named event (on='ticket.commented', 'merge.done', 'ticket.*') or when a ticket enters or leaves a lane (on='lane enter done', 'lane exit in_progress'). The script gets the hook's args " +
            "plus the event payload (ticketId, from, to, branch, tipSha, workerSession, projectId...) as input.args, and runs as you. A loop guard (depth + rate, plugin settings) stops runaway chains; failures are commented on the ticket or mailed to you. Open to every role."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"action":{"type":"string","description":"list | add | remove"},"on":{"type":"string","description":"add: 'lane enter <lane>' or 'lane exit <lane>' (todo, in_progress, pending, done, a user lane) or a named event like ticket.commented"},"script":{"type":"string","description":"add: stored script name (see agenthq_script_run)"},"args":{"type":"string","description":"add: JSON object of fixed args merged under the event payload (default {})"},"id":{"type":"string","description":"add: hook id (default from script + on); remove: the id"},"to":{"type":"string","description":"add, on=mail.incoming only: the ONE session id whose incoming mail the hook handles (default: your own session)"},"disabled":{"type":"boolean","description":"add: store it switched off"},"project":{"type":"string","description":"Project id (default: yours)"}},"required":["action"]}""",
        ).jsonObject
        override fun call(call: ToolCall): ToolResult = handle(call)
    }

    fun handle(call: ToolCall): ToolResult {
        val a = call.arguments
        fun s(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val project = s("project") ?: call.clientProjectId ?: return refuse("needs a project (pass project, or run it from a project session)")
        return try {
            when (val action = s("action")?.lowercase()) {
                "list" -> list(project)
                "add" -> add(call, project, s("on"), s("script"), s("id"), s("to"), a["args"], (a["disabled"] as? JsonPrimitive)?.booleanOrNull == true)
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

    private fun add(call: ToolCall, project: String, on: String?, script: String?, id: String?, to: String?, rawArgs: Any?, disabled: Boolean): ToolResult {
        val parsedOn = HookOn.parse(on ?: return refuse("add needs on (e.g. 'lane enter done' or 'ticket.commented')"))
        val name = script ?: return refuse("add needs script")
        scripts.read(project, name) ?: return refuse("no script '$name' in project $project (looked in .agenthq/scripts/); have: ${runCatching { scripts.names(project) }.getOrDefault(emptyList()).joinToString().ifBlank { "none" }}")
        val args = when (rawArgs) {
            null -> JsonObject(emptyMap())
            is JsonObject -> rawArgs
            is JsonPrimitive -> if (rawArgs.contentOrNull.isNullOrBlank()) JsonObject(emptyMap()) else runCatching { Json.parseToJsonElement(rawArgs.content) as? JsonObject }.getOrNull() ?: return refuse("args must be a JSON object")
            else -> return refuse("args must be a JSON object")
        }
        val isMail = parsedOn is HookOn.Named && parsedOn.pattern == ScriptHooks.INCOMING_EVENT
        if (!isMail && to != null) return refuse("'to' only applies to on=mail.incoming")
        val target = if (isMail) (to ?: call.clientSessionId ?: return refuse("a mail.incoming hook attaches to ONE session: pass to=<session id> (no role-wide or project-wide mail hooks)")) else null
        if (target != null) store.list(project).firstOrNull { it.on == parsedOn && it.to == target && it.script == name }
            ?.let { return refuse("session $target already has script '$name' on mail.incoming (hook ${it.id}); one hook per (session, script)") }
        val hookId = id ?: defaultId(name, parsedOn).let { if (target != null) "$it-${target.take(12)}".take(64).trim('-', '.', '_') else it }
        require(HookStore.validId(hookId)) { "bad hook id '$hookId' (letters, digits, '_', '-', '.'; at most 64)" }
        val byOwner = call.caller.owner && call.clientSessionId == null
        store.add(project, Hook(hookId, parsedOn, name, args, !disabled, call.clientSessionId, call.clientRole, byOwner, target))
        return ToolResult("added hook $hookId: on $parsedOn -> $name${if (disabled) " [disabled]" else ""}")
    }

    private fun remove(project: String, id: String): ToolResult =
        if (store.remove(project, id)) ToolResult("removed hook $id") else refuse("no hook '$id' in project $project")

    private fun defaultId(script: String, on: HookOn): String =
        "$script-${on.toString().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')}".take(64).trim('-', '.', '_')

    private fun refuse(why: String) = ToolResult("$NAME refused: $why", isError = true)

    companion object { const val NAME = "agenthq_script_hook" }
}
