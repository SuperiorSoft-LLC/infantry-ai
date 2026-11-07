// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

/**
 * RUNNER-POOL-3 (docs/RUNNER-POOL.md section 3): one registered runner as placement sees it. Plain data; the pool
 * (RUNNER-POOL-2) fills it from `hello` / `health`.
 */
data class RunnerEntry(
    val name: String,
    /** `up` | `degraded` | `down`; only `up` takes new starts. */
    val health: String = HEALTH_UP,
    val freeSlots: Int = 1,
    /** Project ids this runner agrees to run. */
    val projects: Set<String> = emptySet(),
    /** Harness ids logged in on this runner. */
    val loggedInHarnesses: Set<String> = emptySet(),
) {
    companion object {
        const val HEALTH_UP = "up"
    }
}

/** The outcome of [Placement.pick]: a runner name, or a refusal with its reason (never a silent fallback). */
sealed interface PlacementResult {
    data class Placed(val runner: String) : PlacementResult
    data class Refused(val reason: String) : PlacementResult
}

/** Where a ticket start runs. Pure: pin > routing rule > default, then the named runner (or `any`) is checked. */
object Placement {
    const val LOCAL = "local"
    const val ANY = "any"

    /**
     * RUNNER-AGENT-1, the one precedence: explicit [ticketPin] > [spawnArg] (a spawn's `runner`) > [agentRunner] (the agent
     * profile's `runner`) > [ruleRunner] (the first matching routing rule's `runner`) > [default] (`runners.default`) > local.
     * Blank = unset. [projectId] / [harness] narrow `any` only.
     */
    fun pick(
        ticketPin: String?,
        ruleRunner: String?,
        default: String,
        runners: List<RunnerEntry>,
        projectId: String? = null,
        harness: String? = null,
        spawnArg: String? = null,
        agentRunner: String? = null,
    ): PlacementResult {
        val want = listOf(ticketPin, spawnArg, agentRunner, ruleRunner).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
            ?: default.trim().ifEmpty { LOCAL }
        if (want == ANY) {
            val eligible = runners.filter {
                it.health == RunnerEntry.HEALTH_UP && it.freeSlots > 0 &&
                    (projectId == null || it.name == LOCAL || projectId in it.projects) &&
                    (harness == null || it.name == LOCAL || harness in it.loggedInHarnesses)
            }
            val best = eligible.maxWithOrNull(compareBy<RunnerEntry> { it.freeSlots }.thenBy { it.name == LOCAL })
                ?: return PlacementResult.Refused("no runner is up with a free slot for this ticket")
            return PlacementResult.Placed(best.name)
        }
        val entry = runners.firstOrNull { it.name == want }
        if (entry == null) {
            // "local" is always registered, even when the caller passes an empty list.
            return if (want == LOCAL) PlacementResult.Placed(LOCAL) else PlacementResult.Refused("runner '$want' is not registered")
        }
        if (entry.health != RunnerEntry.HEALTH_UP) return PlacementResult.Refused("runner $want is ${entry.health}")
        if (entry.freeSlots <= 0) return PlacementResult.Refused("runner $want is full")
        return PlacementResult.Placed(want)
    }
}
