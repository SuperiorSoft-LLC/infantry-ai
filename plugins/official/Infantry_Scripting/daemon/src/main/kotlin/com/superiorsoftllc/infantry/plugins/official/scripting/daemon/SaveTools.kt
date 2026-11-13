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
class SaveTools(
    private val store: ScriptStore,
    private val hooks: HookStore,
    private val engines: ScriptEngines,
    /** SCRIPTING-NCDFE-1: a time as people read it ([com.superiorsoftllc.infantry.daemon.api.DaemonApi.displayStamp]); core's DisplayZone is not on a plugin's class path. */
    private val stamp: (Long) -> String,
    /** SCRIPT-ARSENAL-1: who may write the global scope: comma-separated roles (setting `scripting.global.writeRoles`; empty = any role). The owner always may. */
    private val globalWriteRoles: () -> String = { DEFAULT_GLOBAL_WRITERS },
    /** SCRIPT-ARSENAL-1: every project id, to find the hooks that use a global script before it is deleted. */
    private val projects: () -> List<String> = { emptyList() },
    /** SCRIPT-ARSENAL-2: called after a save or delete: (scope, project or null, name). Regenerates the knowledge entry. */
    private val changed: (String, String?, String) -> Unit = { _, _, _ -> },
) {
    private fun scopeOf(c: ToolCall): String? = (arg(c, "scope")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: ScriptStore.PROJECT).takeIf { it == ScriptStore.PROJECT || it == ScriptStore.GLOBAL || it == PluginScripts.SCOPE }

    /** The refusal when the caller may not write the global scope, else null. A setting, not code: no role is special except the owner. */
    private fun globalDenied(c: ToolCall, tool: String): ToolResult? = globalWriteRefusal(c)?.let { refuse(tool, it) }

    /** The reason [c]'s caller may not write the app-wide scope (scripts or hooks), else null. */
    fun globalWriteRefusal(c: ToolCall, what: String = "writing the global scope", instead: String = "save a project copy instead"): String? {
        if (store.global == null) return "this daemon has no global script scope"
        if (c.caller.owner) return null
        val roles = globalWriteRoles().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val role = c.clientRole?.lowercase()
        return if (roles.isEmpty() || (role != null && role in roles)) null
        else "$what is limited to roles ${roles.joinToString()} (setting scripting.global.writeRoles); $instead"
    }

    private fun schema(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun proj(c: ToolCall) = (c.arguments["project"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: c.clientProjectId
    private fun arg(c: ToolCall, k: String) = (c.arguments[k] as? JsonPrimitive)?.contentOrNull
    private fun refuse(tool: String, why: String) = ToolResult("$tool refused: $why", isError = true)

    val save: DaemonTool = object : DaemonTool {
        override val name = SAVE
        override val description = "Save a script of this project (<project>/.infantry/scripts/<name>.<ext>, overwrite allowed), or with scope=global one in the Script Arsenal (global scripts every project can run). The source is parse-checked first and refused with the parse error if it does not load. " +
            "Usable at once by infantry_script_run and infantry_script_hook. Records who saved it. With scope=plugin it edits a script an installed plugin ships (the edit is kept when the plugin updates). Open to every role."
        override val inputSchema = schema("""{"type":"object","properties":{"name":{"type":"string","description":"Script name (letters, digits, _ - .; at most 64)"},"source":{"type":"string","description":"The script source"},"engine":{"type":"string","description":"Engine id (default lua)"},"scope":{"type":"string","enum":["project","global","plugin"],"description":"project (default), global (the app-wide arsenal every project can run; writers are a setting) or plugin (edit a script an installed plugin ships)"},"plugin":{"type":"string","description":"scope=plugin: the module that ships it (default: the only one that does)"},"project":{"type":"string","description":"Project id (default: yours)"}},"required":["name","source"]}""")
        override fun call(call: ToolCall): ToolResult {
            val scope = scopeOf(call) ?: return refuse(SAVE, "scope must be project, global or plugin")
            if (scope == PluginScripts.SCOPE) return editPlugin(call)
            val project = proj(call)
            if (scope == ScriptStore.PROJECT && project == null) return refuse(SAVE, "needs a project (pass project, or run it from a project session)")
            if (scope == ScriptStore.GLOBAL) globalDenied(call, SAVE)?.let { return it }
            val name = arg(call, "name")?.trim().orEmpty()
            val source = arg(call, "source") ?: return refuse(SAVE, "needs source")
            return try {
                store.checkName(name)
                val wanted = arg(call, "engine")?.takeIf { it.isNotBlank() }
                val engine = (if (wanted != null) engines.byId(wanted) ?: return refuse(SAVE, "no engine '$wanted' loaded; have: ${engines.ids().joinToString()}") else engines.default())
                    ?: return refuse(SAVE, "no script engine is loaded")
                engine.check(source)?.let { return refuse(SAVE, "'$name' does not parse: $it") }
                val ext = engine.extensions.first()
                val by = "${call.clientSessionId ?: "owner"} (${call.clientRole ?: "-"})"
                val global = scope == ScriptStore.GLOBAL
                val updated = if (global) store.global!!.write(name, ext, source, by) else store.write(project!!, name, ext, source, by)
                runCatching { changed(scope, project, name) }
                ToolResult("${if (updated) "updated" else "created"} script $name.$ext (${source.toByteArray(Charsets.UTF_8).size} bytes) in ${if (global) "the global scope" else "project $project"}")
            } catch (e: IllegalArgumentException) {
                refuse(SAVE, e.message.orEmpty())
            } catch (e: IllegalStateException) {
                refuse(SAVE, e.message.orEmpty())
            }
        }
    }

    /** PLUGIN-SCRIPTS-1: `scope=plugin`: replace the source of a script a plugin ships (same parse check; the global-writer gate, it is app-wide). */
    private fun editPlugin(call: ToolCall): ToolResult {
        val plugins = store.plugins ?: return refuse(SAVE, "this daemon has no plugin script scope")
        globalWriteRefusal(call, "editing a plugin script (app-wide)", "save a project copy of the same name instead (it wins a lookup)")?.let { return refuse(SAVE, it) }
        val name = arg(call, "name")?.trim().orEmpty()
        val source = arg(call, "source") ?: return refuse(SAVE, "needs source")
        return try {
            store.checkName(name)
            val owners = plugins.modulesOf(name)
            val module = arg(call, "plugin")?.trim()?.takeIf { it.isNotEmpty() }
                ?: owners.singleOrNull()
                ?: return refuse(SAVE, if (owners.isEmpty()) "no plugin ships a script '$name' (save it in scope project or global instead)" else "'$name' is shipped by ${owners.joinToString()}: pass plugin")
            if (module !in owners) return refuse(SAVE, "plugin $module ships no script '$name'")
            val current = plugins.entries().first { it.module == module && it.name == name }
            val engine = engines.byExtension(current.extension) ?: return refuse(SAVE, "no engine loaded for .${current.extension}")
            engine.check(source)?.let { return refuse(SAVE, "'$name' does not parse: $it") }
            plugins.edit(module, name, current.extension, source, "${call.clientSessionId ?: "owner"} (${call.clientRole ?: "-"})")
            ToolResult("updated plugin script $name.${current.extension} of plugin $module (${source.toByteArray(Charsets.UTF_8).size} bytes); the edit is kept when the plugin updates")
        } catch (e: IllegalArgumentException) {
            refuse(SAVE, e.message.orEmpty())
        } catch (e: IllegalStateException) {
            refuse(SAVE, e.message.orEmpty())
        }
    }

    val list: DaemonTool = object : DaemonTool {
        override val name = LIST
        override val description = "List this project's stored scripts, the global scripts from the Script Arsenal and the scripts installed plugins ship (scope plugin:<module>; disabled while the plugin is gone): scope, name, engine, size, modified time, who saved it, and the hooks that use it. Open to every role."
        override val inputSchema = schema("""{"type":"object","properties":{"project":{"type":"string","description":"Project id (default: yours)"},"q":{"type":"string","description":"Search names, purposes and scenarios (header text) of project and global scripts, case-insensitive"}}}""")
        override val annotations: JsonObject = schema("""{"readOnlyHint":true}""")
        override fun call(call: ToolCall): ToolResult {
            val project = proj(call) ?: return refuse(LIST, "needs a project")
            return try {
                val used = hooks.list(project).groupBy { it.script }
                val own = store.entries(project)
                val ownNames = own.map { it.name }.toSet()
                val globals = store.global?.entries().orEmpty()
                val globalNames = globals.map { it.name }.toSet()
                val q = arg(call, "q")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                fun matches(e: ScriptStore.Entry): Boolean {
                    if (q == null) return true
                    val src = (if (e.scope == ScriptStore.GLOBAL) store.global?.readExt(e.name, e.extension) else store.read(project, e.name))?.source.orEmpty()
                    val d = ScriptHeader.parse(e.name, src)
                    return e.name.lowercase().contains(q) || (d != null && (d.purpose + " " + d.scenario).lowercase().contains(q))
                }
                val rows = (own + globals).filter { matches(it) }
                // PLUGIN-SCRIPTS-1: the scripts plugins ship, after the project and global ones (both win a lookup by name).
                val shipped = store.plugins?.entries().orEmpty().filter { e ->
                    q == null || e.name.lowercase().contains(q) || e.description.lowercase().contains(q) || e.module.lowercase().contains(q)
                }
                if (q != null && rows.isEmpty() && shipped.isEmpty()) return ToolResult("no script matches '$q' in project $project, the Script Arsenal or the installed plugins")
                val pluginLines = shipped.joinToString("") { e ->
                    val shadow = if (e.name in ownNames) "  SHADOWED by the project copy" else if (e.name in globalNames) "  SHADOWED by the global" else ""
                    "\n  ${e.name}  scope=${e.scope}  engine=${engines.byExtension(e.extension)?.id ?: e.extension}  ${e.size} bytes  modified ${stamp(e.modifiedMillis)}  saved by ${e.savedBy ?: "(unknown)"}" +
                        "  hooks: ${used[e.name]?.filter { it.plugin == e.module }?.takeIf { it.isNotEmpty() }?.joinToString { it.id } ?: "none"}" +
                        (if (e.edited) "  [edited]" else "") + (if (e.enabled) "" else "  [disabled: plugin not loaded]") + shadow +
                        (if (e.description.isNotEmpty()) "  - ${e.description.take(160)}" else "")
                }
                ToolResult(
                    if (rows.isEmpty() && shipped.isEmpty()) "no scripts in project $project"
                    else "${own.size} project script(s) in project $project, ${globals.size} global, ${shipped.size} from plugins:" + (if (rows.isEmpty()) "" else "\n") + rows.joinToString("\n") { e ->
                        val shadow = when {
                            e.scope == ScriptStore.GLOBAL && e.name in ownNames -> "  SHADOWED by the project copy"
                            e.scope == ScriptStore.PROJECT && e.name in globalNames -> "  shadows the global"
                            else -> ""
                        }
                        "  ${e.name}  scope=${e.scope}  engine=${engines.byExtension(e.extension)?.id ?: e.extension}  ${e.size} bytes  modified ${stamp(e.modifiedMillis)}  saved by ${e.savedBy ?: "(unknown)"}  hooks: ${used[e.name]?.filter { it.plugin == null }?.takeIf { it.isNotEmpty() }?.joinToString { it.id } ?: "none"}$shadow"
                    } + pluginLines,
                )
            } catch (e: IllegalStateException) {
                refuse(LIST, e.message.orEmpty())
            }
        }
    }

    val delete: DaemonTool = object : DaemonTool {
        override val name = DELETE
        override val description = "Delete a stored script of this project (scope=global: from the Script Arsenal); refused while a hook uses it (the answer names the hooks). Open to every role."
        override val inputSchema = schema("""{"type":"object","properties":{"name":{"type":"string"},"scope":{"type":"string","enum":["project","global"],"description":"project (default) or global"},"project":{"type":"string","description":"Project id (default: yours)"}},"required":["name"]}""")
        override fun call(call: ToolCall): ToolResult {
            val scope = scopeOf(call) ?: return refuse(DELETE, "scope must be project or global")
            val name = arg(call, "name")?.trim().orEmpty()
            if (scope == PluginScripts.SCOPE) return refuse(DELETE, "a plugin's scripts go with the plugin: uninstall or disable it (its scripts stay, disabled)")
            if (scope == ScriptStore.GLOBAL) {
                globalDenied(call, DELETE)?.let { return it }
                return try {
                    store.checkName(name)
                    val arsenalUsers = hooks.arsenalHooks().filter { it.script == name }.map { "global:${it.id}" }
                    val users = arsenalUsers + projects().flatMap { p -> runCatching { hooks.list(p) }.getOrDefault(emptyList()).filter { it.script == name && !store.hasProjectCopy(p, name) }.map { "$p:${it.id}" } }
                    if (users.isNotEmpty()) refuse(DELETE, "global script '$name' is used by hook(s) ${users.joinToString()}; remove them first")
                    else if (store.global!!.delete(name)) { runCatching { changed(ScriptStore.GLOBAL, null, name) }; ToolResult("deleted global script $name") } else refuse(DELETE, "no global script '$name'")
                } catch (e: IllegalArgumentException) { refuse(DELETE, e.message.orEmpty()) }
            }
            val project = proj(call) ?: return refuse(DELETE, "needs a project")
            return try {
                store.checkName(name)
                val using = hooks.listOwn(project).filter { it.script == name }
                if (using.isNotEmpty()) return refuse(DELETE, "script '$name' is used by hook(s) ${using.joinToString { it.id }}; remove them first")
                if (store.delete(project, name)) { runCatching { changed(ScriptStore.PROJECT, project, name) }; ToolResult("deleted script $name") } else refuse(DELETE, "no script '$name' in project $project")
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
        /** SCRIPT-ARSENAL-1: default of the `scripting.global.writeRoles` setting. */
        const val DEFAULT_GLOBAL_WRITERS = "pm,owner"
    }
}
