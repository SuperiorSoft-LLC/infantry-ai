package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.api.ConfigParts
import java.util.concurrent.ConcurrentHashMap

/**
 * HARNESS H8b-1: where a harness's inline-config merge (API 2.11.0 `Harness.mergeConfig`) is looked up by harness id, so no caller names a
 * harness. Core code that still owns a harness in place registers with [register]; a plugin harness is reached through [pluginMerge] (the daemon
 * points it at the registry). Registered first, then the plugin.
 */
object HarnessConfigMerge {
    private val core = ConcurrentHashMap<String, (String?, ConfigParts) -> String>()

    /** The plugin harness registry's merge (null = unknown id or none). Set by the daemon at start. */
    @Volatile var pluginMerge: (harnessId: String, existing: String?, parts: ConfigParts) -> String? = { _, _, _ -> null }

    fun register(harnessId: String, merge: (existing: String?, parts: ConfigParts) -> String) { core[harnessId] = merge }

    /** [existing] with [parts] merged by harness [harnessId]; [existing] unchanged (possibly null) when the harness has no merge. */
    fun merge(harnessId: String, existing: String?, parts: ConfigParts): String? =
        core[harnessId]?.invoke(existing, parts) ?: pluginMerge(harnessId, existing, parts) ?: existing
}
