package com.superiorsoftllc.agenthq.daemon

/**
 * HARNESS H6b / H6c: the daemon's own constants for harness contributions, in ONE place. DaemonMain names no harness. The pi harness is
 * a module now (plugins/official/Infantry_Pi): its launch extras (to-do and watch extensions) and sandbox dir come from the plugin's own
 * `session()` / `watch()` / `sandbox()` contributions, so nothing pi-specific is registered here any more; only ids as data remain.
 */
object CoreHarnessContributions {
    /** Harnesses whose usage the meter reads from their own session store, so no event translator applies (ids are data). */
    val meterReadsOwnStore: Set<String> = setOf(
        com.superiorsoftllc.agenthq.stats.SessionStatsService.CLAUDE,
        com.superiorsoftllc.agenthq.stats.SessionStatsService.PI,
        com.superiorsoftllc.agenthq.stats.SessionStatsService.OPENCODE,
    )

    /** Harnesses whose watch hook runs whatever the approval mode (pi has no approval prompt of its own). */
    val watchApprovalExempt: Set<String> = setOf(com.superiorsoftllc.agenthq.stats.SessionStatsService.PI)

    fun register() {
        WatchHooks.approvalExempt = watchApprovalExempt
        // H8b-1: opencode's inline-config merge, registered through the same slot a plugin harness fills (H8b-3 moves it into the plugin).
        com.superiorsoftllc.agenthq.harness.HarnessConfigMerge.register(com.superiorsoftllc.agenthq.stats.SessionStatsService.OPENCODE) { existing, parts ->
            var c = existing ?: "{}"
            parts.mcp?.let { c = AgentHqMcp.mergeOpenCode(c, AgentHqMcp.Launch(it.command.first(), it.command.drop(1), it.env)) }
            parts.watchPlugin?.let { c = WatchHooks.appendOpenCodePlugin(c, it) }
            c
        }
    }
}
