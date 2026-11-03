package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings
import com.superiorsoftllc.infantry.daemon.api.processes.Processes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Ticket 66153313 / STALL-1 / STACK-NEUTRAL-1, moved out of core by STACK-NEUTRAL-2: what a worker session's launch gets while the
 * watch tooling is on (`limits.watch.enabled` = 1): the build priority and caps (and what each tool profile's `env` templates make of
 * them), and the watch shims first on PATH with `INFANTRY_WATCH=on`. Off (or this plugin absent / disabled) = nothing here: the host's
 * base env says `INFANTRY_WATCH=off`, so a session gets no build env, no shims and no harness hooks.
 */
internal object WatchLaunchEnv {
    const val PRIORITY_ENV = "INFANTRY_BUILD_PRIORITY"
    const val MAX_WORKERS_ENV = "INFANTRY_BUILD_MAX_WORKERS"
    const val MAX_FORKS_ENV = "INFANTRY_BUILD_MAX_FORKS"
    const val PRIORITY_KEY = "limits.build.priority"
    const val MAX_WORKERS = "maxWorkers"
    const val MAX_PARALLEL_FORKS = "maxParallelForks"

    private val PRIORITIES = listOf("normal", "belownormal", "idle")
    private const val DEFAULT_PRIORITY = "belownormal"

    /** `limits.watch.enabled` (0 = off). An unreadable switch is off. */
    fun enabled(settings: PluginSettings): Boolean = runCatching { (settings.limit("limits.watch.enabled") ?: 0L) != 0L }.getOrDefault(false)

    /** `belownormal`, `below_normal`, `below-normal` and `BelowNormal` all name the same class; null for anything else. */
    fun parsePriority(text: String?): String? {
        val k = text?.trim()?.lowercase()?.replace("_", "")?.replace("-", "") ?: return null
        return PRIORITIES.firstOrNull { it == k }
    }

    fun priority(settings: PluginSettings): String =
        settings.stored(PRIORITY_KEY)
            ?.let { runCatching { (Json.parseToJsonElement(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.getOrNull() }
            ?.let(::parsePriority) ?: DEFAULT_PRIORITY

    /** The env of one launch; empty while the watch is off. */
    fun env(os: Processes, settings: PluginSettings): Map<String, String> {
        if (!enabled(settings)) return emptyMap()
        val maxWorkers = (settings.limit("limits.build.maxWorkers") ?: 0L).toInt()
        val maxForks = (settings.limit("limits.build.maxParallelForks") ?: 0L).toInt()
        val out = LinkedHashMap<String, String>()
        out[PRIORITY_ENV] = priority(settings)
        if (maxWorkers > 0) out[MAX_WORKERS_ENV] = maxWorkers.toString()
        if (maxForks > 0) out[MAX_FORKS_ENV] = maxForks.toString()
        out.putAll(templates(os, maxWorkers, maxForks))
        // STALL-1: the shims first on PATH (gradle/mvn/npm/yarn/pnpm run watched) unless the owner turned the forced watch off.
        val forced = (settings.limit("limits.watch.forced") ?: 1L) != 0L
        val dir = os.shimDir()
        if (forced && dir != null) {
            val inheritedPath = os.inheritedEnv("PATH")
            out["PATH"] = File(dir).absolutePath + (inheritedPath?.takeIf { it.isNotBlank() }?.let { File.pathSeparator + it } ?: "")
            out["INFANTRY_SHIM_DIR"] = File(dir).absolutePath
            out["INFANTRY_WATCH"] = "on"
        }
        return out
    }

    /** Every profile's env templates filled from the limits, in profile order; the daemon's own value of a variable is kept in front. */
    fun templates(os: Processes, maxWorkers: Int, maxParallelForks: Int): Map<String, String> {
        val values = mapOf(MAX_WORKERS to maxWorkers, MAX_PARALLEL_FORKS to maxParallelForks)
        val out = LinkedHashMap<String, String>()
        for (t in os.toolProfiles().flatMap { it.env }) {
            if (t.onlyIf != null && (values[t.onlyIf] ?: 0) <= 0) continue
            val v = values.entries.fold(t.value) { acc, (k, n) -> acc.replace("\${$k}", n.toString()) }
            val before = out[t.name] ?: (if (t.inherit) os.inheritedEnv(t.name)?.trim()?.takeIf { it.isNotEmpty() } else null)
            out[t.name] = if (before == null) v else before + t.join + v
        }
        return out
    }
}
