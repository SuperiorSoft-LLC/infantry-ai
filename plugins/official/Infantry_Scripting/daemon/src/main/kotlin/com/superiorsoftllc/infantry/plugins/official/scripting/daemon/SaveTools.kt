// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** SCRIPT-SAVE-1: `infantry_script_save|list|delete`, so any agent writes a script at runtime with no file access. Open to every role (D18); the caller is recorded, never tested. */
class SaveTools(private val store: ScriptStore, private val hooks: HookStore, private val engines: ScriptEngines) {
    private fun schema(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun proj(c: ToolCall) = (c.arguments["project"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: c.clientProjectId
    private fun arg(c: ToolCall, k: String) = (c.arguments[k] as? JsonPrimitive)?.contentOrNull
    private fun refuse(tool: String, why: String) = ToolResult("$tool refused: $why", isError = true)

    val save: DaemonTool = object : DaemonTool {
        override val name = SAVE
        override val description = "Save a script of this project (<project>/.infantry/scripts/<name>.<ext>, overwrite allowed). The source is parse-checked first and refused with the parse error if it does not load. " +
            "Usable at once by infantry_script_run and infantry_script_hook. Records who saved it. Open to every role."
        override val inputSchema = schema("""{"type":"object","properties":{"name":{"type":"string","description":"Script name (letters, digits, _ - .; at most 64)"},"source":{"type":"string","description":"The script source"},"engine":{"type":"string","description":"Engine id (default lua)"},"project":{"type":"string","description":"Project id (default: yours)"}},"required":["name","source"]}""")
        override fun call(call: ToolCall): ToolResult {
            val project = proj(call) ?: return refuse(SAVE, "needs a project (pass project, or run it from a project session)")
            val name = arg(call, "name")?.trim().orEmpty()
            val source = arg(call, "source") ?: return refuse(SAVE, "needs source")
            return try {
                store.checkName(name)
                val wanted = arg(call, "engine")?.takeIf { it.isNotBlank() }
                val engine = (if (wanted != null) engines.byId(wanted) ?: return refuse(SAVE, "no engine '$wanted' loaded; have: ${engines.ids().joinToString()}") else engines.default())
                    ?: return refuse(SAVE, "no script engine is loaded")
                engine.check(source)?.let { return refuse(SAVE, "'$name' does not parse: $it") }
                val ext = engine.extensions.first()
                val updated = store.write(project, name, ext, source, "${call.clientSessionId ?: "owner"} (${call.clientRole ?: "-"})")
                ToolResult("${if (updated) "updated" else "created"} script $name.$ext (${source.toByteArray(Charsets.UTF_8).size} bytes) in project $project")
            } catch (e: IllegalArgumentException) {
                refuse(SAVE, e.message.orEmpty())
            } catch (e: IllegalStateException) {
                refuse(SAVE, e.message.orEmpty())
            }
        }
    }

    val list: DaemonTool = object : DaemonTool {
        override val name = LIST
        override val description = "List this project's stored scripts: name, engine, size, modified time, who saved it, and the hooks that use it. Open to every role."
        override val inputSchema = schema("""{"type":"object","properties":{"project":{"type":"string","description":"Project id (default: yours)"}}}""")
        override val annotations: JsonObject = schema("""{"readOnlyHint":true}""")
        override fun call(call: ToolCall): ToolResult {
            val project = proj(call) ?: return refuse(LIST, "needs a project")
            return try {
                val used = hooks.list(project).groupBy { it.script }
                val rows = store.entries(project)
                ToolResult(
                    if (rows.isEmpty()) "no scripts in project $project"
                    else "${rows.size} script(s) in project $project:\n" + rows.joinToString("\n") { e ->
                        "  ${e.name}  engine=${engines.byExtension(e.extension)?.id ?: e.extension}  ${e.size} bytes  modified ${com.superiorsoftllc.infantry.DisplayZone.stamp(e.modifiedMillis)}  saved by ${e.savedBy ?: "(unknown)"}  hooks: ${used[e.name]?.joinToString { it.id } ?: "none"}"
                    },
                )
            } catch (e: IllegalStateException) {
                refuse(LIST, e.message.orEmpty())
            }
        }
    }

    val delete: DaemonTool = object : DaemonTool {
        override val name = DELETE
        override val description = "Delete a stored script of this project; refused while a hook uses it (the answer names the hooks). Open to every role."
        override val inputSchema = schema("""{"type":"object","properties":{"name":{"type":"string"},"project":{"type":"string","description":"Project id (default: yours)"}},"required":["name"]}""")
        override fun call(call: ToolCall): ToolResult {
            val project = proj(call) ?: return refuse(DELETE, "needs a project")
            val name = arg(call, "name")?.trim().orEmpty()
            return try {
                store.checkName(name)
                val using = hooks.list(project).filter { it.script == name }
                if (using.isNotEmpty()) return refuse(DELETE, "script '$name' is used by hook(s) ${using.joinToString { it.id }}; remove them first")
                if (store.delete(project, name)) ToolResult("deleted script $name") else refuse(DELETE, "no script '$name' in project $project")
            } catch (e: IllegalArgumentException) {
                refuse(DELETE, e.message.orEmpty())
            } catch (e: IllegalStateException) {
                refuse(DELETE, e.message.orEmpty())
            }
        }
    }

    companion object {
        const val SAVE = "infantry_script_save"
        const val LIST = "infantry_script_list"
        const val DELETE = "infantry_script_delete"
    }
}
