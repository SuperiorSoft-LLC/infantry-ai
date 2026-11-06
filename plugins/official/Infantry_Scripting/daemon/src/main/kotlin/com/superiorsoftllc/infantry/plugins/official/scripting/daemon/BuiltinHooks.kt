// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * BRIDGE-EVENTS-1: a hook the Scripting plugin SHIPS, so a default process (resume the turns a restart cut) works with no setup and is
 * still a script, not core code. Data in the plugin jar: `builtin-scripts/builtins.json` lists the hooks, the scripts sit next to it.
 *
 * A built-in hook runs daemon-wide (not per project) on its named event, as the daemon's automation identity (owner, no session),
 * through the same engines, dispatcher, loop guard and run log as a project hook. [configArgs] maps a script arg to a plugin setting
 * (`daemon.config` of this plugin), read at each run: `"true"` / `"false"` become booleans, anything else stays text. The setting
 * `scripting.builtins.off` (comma separated ids) turns hooks off, so a developer can replace one with a project hook of their own.
 */
data class BuiltinHook(
    val id: String,
    val on: HookOn,
    val script: String,
    val extension: String,
    val source: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val configArgs: Map<String, String> = emptyMap(),
) {
    /** The script args: [args], overlaid by the settings named in [configArgs] that are set. */
    fun resolvedArgs(config: (String) -> String?): JsonObject {
        val m = LinkedHashMap(args)
        for ((arg, key) in configArgs) {
            val v = config(key) ?: continue
            m[arg] = when (v.trim().lowercase()) { "true" -> JsonPrimitive(true); "false" -> JsonPrimitive(false); else -> JsonPrimitive(v) }
        }
        return JsonObject(m)
    }

    companion object {
        const val DIR = "builtin-scripts"
        const val INDEX = "$DIR/builtins.json"
        /** Setting: comma-separated built-in hook ids that do not run. */
        const val OFF_SETTING = "scripting.builtins.off"

        fun off(config: (String) -> String?): Set<String> =
            config(OFF_SETTING).orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        /** Reads [INDEX] and its scripts through [resource] (a path -> its text, or null). A bad entry is skipped with a [log] line; never throws. */
        fun load(resource: (String) -> String?, log: (String) -> Unit = {}): List<BuiltinHook> {
            val index = resource(INDEX) ?: return emptyList()
            val hooks = runCatching { Json.parseToJsonElement(index).jsonObject["hooks"]!!.jsonArray }.getOrElse { log("builtins: $INDEX unreadable: ${it.message}"); return emptyList() }
            return hooks.mapNotNull { el ->
                runCatching {
                    val o = el.jsonObject
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    val id = requireNotNull(s("id")) { "no id" }
                    val file = requireNotNull(s("script")) { "hook $id has no script" }
                    require(!file.contains('/') && !file.contains('\\') && file.contains('.')) { "hook $id: script must be a plain file name with its extension" }
                    val source = requireNotNull(resource("$DIR/$file")) { "hook $id: no $DIR/$file in the jar" }
                    BuiltinHook(
                        id, HookOn.parse(requireNotNull(s("on")) { "hook $id has no on" }), file.substringBeforeLast('.'), file.substringAfterLast('.'), source,
                        (o["args"] as? JsonObject) ?: JsonObject(emptyMap()),
                        (o["configArgs"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
                    )
                }.onFailure { log("builtins: entry skipped: ${it.message}") }.getOrNull()
            }
        }

        /** The plugin jar's own resources. */
        fun fromJar(log: (String) -> Unit = {}): List<BuiltinHook> =
            load({ path -> BuiltinHook::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) } }, log)
    }
}
