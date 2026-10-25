package com.superiorsoftllc.agenthq.settings

/**
 * OWNER RULE (2026-10-25): no hard-coded restriction. Every cap, threshold and timeout of the daemon is an entry here:
 * a documented, overridable setting with a default, never a constant a user cannot see or change.
 *
 * Resolution of one limit, first hit wins: the stored daemon setting (`settings_set`, Settings UI, `agenthq` CLI) ->
 * the environment ([LimitDef.envNames]) -> [LimitDef.default]. `0` means UNLIMITED where [LimitDef.zeroIsUnlimited].
 * Entries are added stage by stage (S1 = concurrency); `LimitsGuardTest` fails when a limit-like constant appears in
 * the main sources that is neither routed through this registry nor on the allowlist with a reason.
 */
data class LimitDef(
    /** The setting key, `limits.<group>.<name>`. */
    val key: String,
    val default: Long,
    val unit: String,
    val description: String,
    /** Smallest accepted value (0 where [zeroIsUnlimited]). */
    val min: Long = 0,
    /** Largest accepted value; null = no ceiling. */
    val max: Long? = null,
    val zeroIsUnlimited: Boolean = true,
    /** Extra environment names read before the default; `AGENTHQ_LIMIT_<KEY>` is always read first. */
    val legacyEnv: List<String> = emptyList(),
) {
    /** `limits.concurrency.maxPerPod` -> `AGENTHQ_LIMIT_CONCURRENCY_MAX_PER_POD`. */
    val env: String = "AGENTHQ_LIMIT_" + key.removePrefix(PREFIX).replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace('.', '_').uppercase()

    val envNames: List<String> get() = listOf(env) + legacyEnv

    /** The sentence every refusal ends with: which setting, where to change it. */
    fun hint(): String =
        "Change it with the setting '$key' (Settings > Limits, `agenthq settings set $key <n>`, or env $env)" +
            (if (zeroIsUnlimited) "; 0 = unlimited" else "") + "."

    fun accepts(value: Long): Boolean = value >= min && (max == null || value <= max)

    fun range(): String = "$min.." + (max?.toString() ?: "no ceiling")

    companion object {
        const val PREFIX = "limits."
    }
}

object LimitRegistry {
    val MAX_PER_POD = LimitDef(
        "limits.concurrency.maxPerPod", 4, "sessions",
        "How many live sessions a session (or MCP connection) may grow its pod by; the worker concurrency cap.",
        legacyEnv = listOf("AGENTHQ_POD_SPAWN_CAP"),
    )
    val MAX_DEPTH = LimitDef(
        "limits.concurrency.maxDepth", 2, "levels",
        "How deep spawns from inside sessions may nest (daemon pod guard and MCP write tools share this one value); 0 = sessions may not spawn at all, set a high number for no practical limit.",
        zeroIsUnlimited = false,
        legacyEnv = listOf("AGENTHQ_POD_SPAWN_DEPTH"),
    )
    val MCP_MAX_SPAWNS = LimitDef(
        "limits.concurrency.mcpMaxSpawns", 4, "sessions",
        "How many sessions one MCP connection may hold open at once (`--max-spawns` / .agenthq/mcp.json lower or raise it per connection).",
    )
    val LANE_DEFAULT = LimitDef(
        "limits.concurrency.laneDefault", 1, "agents",
        "Agents of one role that may run at once in a project, when the project sets no lane cap of its own.",
    )
    val PACING_CEILING = LimitDef(
        "limits.pacing.maxWorkersCeiling", 50, "workers",
        "Highest worker count the usage pacing rule (`usage.pacing`) accepts for maxWorkers and tier workers.",
        min = 1, zeroIsUnlimited = false,
    )

    val ALL: List<LimitDef> = listOf(MAX_PER_POD, MAX_DEPTH, MCP_MAX_SPAWNS, LANE_DEFAULT, PACING_CEILING)

    private val byKey = ALL.associateBy { it.key }

    fun get(key: String): LimitDef? = byKey[key]

    /** [def] resolved from [env] alone (no stored setting): the first set name wins; a bad value is an error naming it. */
    fun fromEnv(def: LimitDef, env: (String) -> String?): Long {
        for (name in def.envNames) {
            val raw = env(name)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val v = raw.toLongOrNull()?.takeIf { def.accepts(it) }
                ?: throw IllegalArgumentException("$name must be a whole number in ${def.range()}, was '$raw'")
            return v
        }
        return def.default
    }

    /** 0 on an unlimited-capable limit is "no limit". */
    fun cap(value: Long, def: LimitDef): Int =
        if (def.zeroIsUnlimited && value == 0L) Int.MAX_VALUE else value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
