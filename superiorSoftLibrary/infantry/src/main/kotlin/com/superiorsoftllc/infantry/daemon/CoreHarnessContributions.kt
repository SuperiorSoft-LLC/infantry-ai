package com.superiorsoftllc.infantry.daemon

/**
 * HARNESS H6b / H6c: the daemon's own constants for harness contributions, in ONE place. DaemonMain names no harness. The pi harness is
 * a module now (plugins/official/Infantry_Pi): its launch extras (to-do and watch extensions) and sandbox dir come from the plugin's own
 * `session()` / `watch()` / `sandbox()` contributions, so nothing pi-specific is registered here any more; only ids as data remain.
 */
object CoreHarnessContributions {
    /** Harnesses whose usage the meter reads from their own session store, so no event translator applies (ids are data). */
    val meterReadsOwnStore: Set<String> = setOf(
        com.superiorsoftllc.infantry.stats.SessionStatsService.CLAUDE,
        com.superiorsoftllc.infantry.stats.SessionStatsService.PI,
        "opencode", // the opencode harness module (Infantry_OpenCode); id as data
    )

    /** Harnesses whose watch hook runs whatever the approval mode (pi has no approval prompt of its own). */
    val watchApprovalExempt: Set<String> = setOf(com.superiorsoftllc.infantry.stats.SessionStatsService.PI)

    fun register() {
        WatchHooks.approvalExempt = watchApprovalExempt
    }
}
