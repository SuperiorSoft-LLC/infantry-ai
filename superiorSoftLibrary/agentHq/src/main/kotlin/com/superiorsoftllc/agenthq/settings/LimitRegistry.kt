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
    /** What 0 means where [zeroIsUnlimited] (shown in hints and refusals). */
    val zeroMeans: String = "unlimited",
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
            (if (zeroIsUnlimited) "; 0 = $zeroMeans" else "") + "."

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
        "limits.concurrency.maxDepth", 1, "levels",
        "How deep spawns from inside sessions may nest (daemon pod guard and MCP write tools share this one value); 1 = a session may spawn, its spawns may not; 0 = sessions may not spawn at all; set a high number for no practical limit.",
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

    /** Ticket 66153313 (contract 1.132.0): Gradle worker cap handed to worker sessions' builds. */
    val BUILD_MAX_WORKERS = LimitDef(
        "limits.build.maxWorkers", com.superiorsoftllc.agenthq.runner.BuildLimits.defaultMaxWorkers().toLong(), "workers",
        "Gradle workers (org.gradle.workers.max) a worker session's build may use; default logical cores - 2.",
        max = 4096,
    )
    val BUILD_MAX_PARALLEL_FORKS = LimitDef(
        "limits.build.maxParallelForks", 0, "forks",
        "Test JVM forks per test task in a worker session's build.",
        max = 4096, zeroMeans = "the build script's own default",
    )
    val CPU_ALERT_PERCENT = LimitDef(
        "limits.cpu.alertPercent", 90, "%",
        "Share of all cores agent processes may hold before the sustained-CPU attention item is raised.",
        min = 1, max = 100, zeroIsUnlimited = false,
    )
    val CPU_ALERT_MINUTES = LimitDef(
        "limits.cpu.alertMinutes", 10, "minutes",
        "How long agent processes must stay above limits.cpu.alertPercent before the attention item is raised.",
        max = 1440, zeroMeans = "alert off",
    )

    /** S2: the unattended-run watchdog and the silent sweeper (SessionManager), read at every pass. */
    val TICKET_DEADLINE_MINUTES = LimitDef(
        "limits.watchdog.ticketDeadlineMinutes", 180, "minutes",
        "How long an unattended ticket worker may run before the watchdog stops it (a gate run alone can take an hour).",
        zeroMeans = "no deadline", legacyEnv = listOf("AGENTHQ_TICKET_DEADLINE_MIN"),
    )
    val UNATTENDED_DEADLINE_MINUTES = LimitDef(
        "limits.watchdog.deadlineMinutes", 60, "minutes",
        "How long an unattended run that is not a ticket worker (a pod node, a spawned helper) may run before the watchdog stops it.",
        zeroMeans = "no deadline",
    )
    val STALL_MINUTES = LimitDef(
        "limits.watchdog.stallMinutes", 15, "minutes",
        "How long an unattended run may print nothing (with no bg job or gate run alive) before the watchdog stops it as stalled.",
        zeroMeans = "never stopped for silence",
    )
    val KILL_RETRY_MINUTES = LimitDef(
        "limits.watchdog.killRetryMinutes", 5, "minutes",
        "How long after a watchdog stop that did not end the run the stop is retried, forced.",
        zeroMeans = "never retried",
    )
    val SILENT_MINUTES = LimitDef(
        "limits.watchdog.silentMinutes", 20, "minutes",
        "How long a busy session may print nothing before it is flagged silent (flag only; a provably dead one is ended).",
        zeroMeans = "never flagged",
    )
    val IDLE_NOTICE_MINUTES = LimitDef(
        "limits.watchdog.idleNoticeMinutes", 60, "minutes",
        "How long a ticket worker may wait at rest (turn ended cleanly, ticket open, no mail to or from it) before its starter gets one note that it is waiting.",
        zeroMeans = "never noted",
    )

    /** GAP-006b: the in-progress worker invariant's grace (see TicketBoard.sweepWorkers). */
    val UNBRIEFED_GRACE_MINUTES = LimitDef(
        "limits.watchdog.unbriefedGraceMinutes", 3, "minutes",
        "How long an in-progress ticket's worker may go without opening a provider session before the board restarts it (once; at rest) or flags it (busy, or no worker).",
        zeroMeans = "never swept",
    )

    /** Ticket 3b30398d: the process-watch pass (ROGUE-1 ledger, CPU sampling), read before every pass. */
    val PROCESS_WATCH_INTERVAL_SECONDS = LimitDef(
        "limits.processes.watchIntervalSeconds", 10, "seconds",
        "How often the process watch walks the session process trees (rogue-process ledger, CPU sampling); also the delay of the start-up cleanup.",
        min = 1, max = 3600, zeroIsUnlimited = false,
    )

    val ALL: List<LimitDef> = listOf(
        MAX_PER_POD, MAX_DEPTH, MCP_MAX_SPAWNS, LANE_DEFAULT, PACING_CEILING,
        BUILD_MAX_WORKERS, BUILD_MAX_PARALLEL_FORKS, CPU_ALERT_PERCENT, CPU_ALERT_MINUTES,
        TICKET_DEADLINE_MINUTES, UNATTENDED_DEADLINE_MINUTES, STALL_MINUTES, KILL_RETRY_MINUTES, SILENT_MINUTES, IDLE_NOTICE_MINUTES, UNBRIEFED_GRACE_MINUTES,
        PROCESS_WATCH_INTERVAL_SECONDS,
    )

    /** A minutes limit as milliseconds; 0 on an unlimited-capable one = [Long.MAX_VALUE] (never reached). */
    fun minutesMs(value: Long, def: LimitDef): Long =
        if (def.zeroIsUnlimited && value == 0L) Long.MAX_VALUE else value * 60_000L

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
