package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.ServiceEndpoint
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** SCRIPT-2: owns the open ScriptEngine extension point (endpoints under the `scripting.engine/` prefix) and the limits settings. Later tickets add the callers. */
class ScriptingDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val engines = ScriptEngines({ api.services.endpoints(ScriptEngineWire.PREFIX) }, { api.config("scripting.defaultEngine")?.takeIf { it.isNotBlank() } ?: "lua" })
        val limits = ScriptingLimits { api.config(it) }
        val tools = ScriptTools(
            api.tools, engines, ScriptStore({ api.files(it) }, { engines.extensions() }), limits,
            { ScriptAliases.parse(api.config("scripting.aliases")) }, { api.config("scripting.maxPhases")?.trim()?.toIntOrNull() ?: 3 },
        )
        api.tools.register(tools.run)
        api.tools.register(tools.api)
        api.log("scripting ready: engines=${engines.ids()} limits=${limits.current()}")
    }
}

/**
 * The registry: every engine published under the extension point, by id or file extension. [source] is read at each lookup, so
 * engines that load or unload later are seen at once. [defaultId] is data (the `scripting.defaultEngine` setting).
 */
class ScriptEngines(private val source: () -> Map<String, ServiceEndpoint>, private val defaultId: () -> String = { "lua" }) {
    private fun engines(): List<ScriptEngine> = source().filterKeys { it.startsWith(ScriptEngineWire.PREFIX) }.values.mapNotNull(ScriptEngineWire::remote)

    fun ids(): Set<String> = engines().map { it.id }.toSortedSet()
    fun byId(id: String): ScriptEngine? = engines().firstOrNull { it.id == id }
    fun byExtension(ext: String): ScriptEngine? = ext.trim().trimStart('.').lowercase().let { e -> engines().firstOrNull { e in it.extensions } }
    fun extensions(): List<String> = engines().flatMap { it.extensions }.distinct()
    /** engine id -> its binding text (script_api). */
    fun bindings(): Map<String, String> = engines().associate { it.id to it.binding }
    fun default(): ScriptEngine? = byId(defaultId())
}

/** The Scripting plugin's own limits, read from its `daemon.config` at each run; a bad value falls back to the default. */
class ScriptingLimits(private val config: (String) -> String?) {
    fun current(): ScriptLimits {
        val d = ScriptLimits()
        fun pick(key: String, range: LongRange, default: Long) = config(key)?.trim()?.toLongOrNull()?.takeIf { it in range } ?: default
        return ScriptLimits(
            pick("scripting.maxSteps", 1_000L..1_000_000_000L, d.maxSteps),
            pick("scripting.timeoutMs", 10L..600_000L, d.timeoutMs),
            pick("scripting.maxAllocBytes", 65_536L..1_073_741_824L, d.maxAllocBytes),
        )
    }
}

/** The alias table (short name -> registry tool name) is data: the JSON object in the `scripting.aliases` setting. A bad value is an empty table. */
object ScriptAliases {
    fun parse(text: String?): Map<String, String> = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(text.orEmpty().ifBlank { "{}" }).jsonObject
            .mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())
}
