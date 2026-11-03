package com.superiorsoftllc.infantry.executables

import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.persistence.SettingRow
import com.superiorsoftllc.infantry.protocol.CliLocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * CLIPATH-1: the overrides the user set in Settings, durable in the daemon's `settings` table under
 * `cli.executable.<toolId>` (a JSON string; `""` = cleared, since the table has no delete). Kept apart from
 * [com.superiorsoftllc.infantry.settings.SettingsService]'s closed key set: these are read and written only through the
 * `cli_locations` / `cli_location_set` frames, which validate the path and answer with the resolution.
 */
class StoredExecutableOverrides(private val store: InfantryStore) : ExecutableOverrides {
    override fun get(toolId: String): String? {
        val stored = store.settings.get(key(toolId))?.value ?: return null
        val parsed = runCatching { Json.parseToJsonElement(stored) as? JsonPrimitive }.getOrNull()
        return parsed?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun set(toolId: String, path: String?) {
        store.settings.put(SettingRow(key(toolId), JsonPrimitive(path?.trim().orEmpty()).toString(), System.currentTimeMillis()))
    }

    companion object {
        const val KEY_PREFIX = "cli.executable."
        fun key(toolId: String): String = KEY_PREFIX + toolId
    }
}

/**
 * CLIPATH-1: what the `cli_locations` / `cli_location_set` frames do. [tools] is read on every call so a descriptor
 * harness registered later is listed; [resolver] must be the one launches use (the daemon passes the installed one).
 */
class CliLocationService(
    private val resolver: ExecutableResolver,
    private val overrides: ExecutableOverrides,
    private val tools: () -> List<CliTool>,
) {
    fun list(): List<CliLocation> = tools().map { describe(resolver.resolve(it)) }

    /**
     * Store [path] as [toolId]'s override, or clear it when [path] is null/blank. An unknown tool or a path that is not
     * an executable file is refused with an [IllegalArgumentException] carrying the reason; nothing is stored then.
     */
    fun set(toolId: String, path: String?): List<CliLocation> {
        val tool = tools().firstOrNull { it.id == toolId }
            ?: throw IllegalArgumentException("unknown CLI '$toolId' (known: ${tools().joinToString { it.id }})")
        val trimmed = path?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed != null) resolver.validate(tool, trimmed)?.let { throw IllegalArgumentException("${tool.label}: $it") }
        overrides.set(tool.id, trimmed)
        return list()
    }

    companion object {
        fun describe(resolution: ExecutableResolver.Resolution): CliLocation = CliLocation(
            id = resolution.tool.id,
            label = resolution.tool.label,
            path = resolution.path,
            source = resolution.source?.wire,
            override = resolution.override,
            tried = resolution.tried,
            reason = resolution.reason,
        )
    }
}
