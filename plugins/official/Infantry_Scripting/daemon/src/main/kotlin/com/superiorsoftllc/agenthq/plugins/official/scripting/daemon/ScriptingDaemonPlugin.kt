package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.ServiceEndpoint

/** SCRIPT-2: owns the open ScriptEngine extension point (endpoints under the `scripting.engine/` prefix) and the limits settings. Later tickets add the callers. */
class ScriptingDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        val engines = ScriptEngines({ api.services.endpoints(ScriptEngineWire.PREFIX) }, { api.config("scripting.defaultEngine")?.takeIf { it.isNotBlank() } ?: "lua" })
        val limits = ScriptingLimits { api.config(it) }
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
