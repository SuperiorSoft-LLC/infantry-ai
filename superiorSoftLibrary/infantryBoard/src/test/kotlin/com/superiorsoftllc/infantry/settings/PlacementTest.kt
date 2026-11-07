// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** RUNNER-POOL-3: pin > rule > default, and `any` / down / full / unknown (docs/RUNNER-POOL.md section 3). */
class PlacementTest {
    private val local = RunnerEntry("local", freeSlots = 1)
    private val s1 = RunnerEntry("server-1", freeSlots = 2, projects = setOf("p"), loggedInHarnesses = setOf("claude"))

    private fun placed(r: PlacementResult) = (r as PlacementResult.Placed).runner
    private fun refused(r: PlacementResult) = (r as PlacementResult.Refused).reason

    @Test fun pinBeatsRuleBeatsDefault() {
        val all = listOf(local, s1)
        assertEquals("local", placed(Placement.pick("local", "server-1", "server-1", all)))
        assertEquals("server-1", placed(Placement.pick(null, "server-1", "local", all)))
        assertEquals("server-1", placed(Placement.pick("  ", "server-1", "local", all)))
        assertEquals("server-1", placed(Placement.pick(null, null, "server-1", all)))
        assertEquals("local", placed(Placement.pick(null, null, "local", all)))
    }

    @Test fun spawnAndAgentRunnerPrecedence() {
        val all = listOf(local, s1)
        // ticket pin > spawn arg > agent > rule > default
        assertEquals("local", placed(Placement.pick("local", "server-1", "server-1", all, spawnArg = "server-1", agentRunner = "server-1")))
        assertEquals("local", placed(Placement.pick(null, "server-1", "server-1", all, spawnArg = "local", agentRunner = "server-1")))
        assertEquals("server-1", placed(Placement.pick(null, "local", "local", all, agentRunner = "server-1")))
        assertEquals("server-1", placed(Placement.pick(null, null, "local", all, spawnArg = " ", agentRunner = "server-1")))
        assertEquals("local", placed(Placement.pick(null, null, "local", all)))
        // a paused / down / unknown runner is refused, never a silent local fallback
        assertEquals("runner server-1 is down", refused(Placement.pick(null, null, "local", listOf(local, s1.copy(health = "down")), agentRunner = "server-1")))
        assertEquals("runner 'nope' is not registered", refused(Placement.pick(null, null, "local", all, spawnArg = "nope")))
    }

    @Test fun onlyLocalAlwaysResolvesToLocal() {
        assertEquals("local", placed(Placement.pick(null, null, "local", listOf(local))))
        assertEquals("local", placed(Placement.pick(null, null, "local", emptyList())))
        assertEquals("local", placed(Placement.pick("any", null, "local", listOf(local))))
    }

    @Test fun anyPicksMostFreeSlotsAndTiesGoToLocal() {
        assertEquals("server-1", placed(Placement.pick("any", null, "local", listOf(local, s1), "p", "claude")))
        assertEquals("local", placed(Placement.pick("any", null, "local", listOf(s1.copy(freeSlots = 1), local), "p", "claude")))
    }

    @Test fun anySkipsRunnersThatAreDownFullOrLackProjectOrHarness() {
        assertEquals("local", placed(Placement.pick("any", null, "local", listOf(local, s1.copy(health = "down")), "p", "claude")))
        assertEquals("local", placed(Placement.pick("any", null, "local", listOf(local, s1.copy(freeSlots = 0)), "p", "claude")))
        assertEquals("local", placed(Placement.pick("any", null, "local", listOf(local, s1), "other", "claude")))
        assertEquals("local", placed(Placement.pick("any", null, "local", listOf(local, s1), "p", "codex")))
        assertEquals("no runner is up with a free slot for this ticket", refused(Placement.pick("any", null, "local", listOf(local.copy(freeSlots = 0), s1.copy(health = "degraded")))))
    }

    @Test fun aNamedRunnerThatIsDownOrFullIsRefusedWithoutFallback() {
        assertEquals("runner server-1 is down", refused(Placement.pick("server-1", null, "local", listOf(local, s1.copy(health = "down")))))
        assertEquals("runner server-1 is degraded", refused(Placement.pick(null, "server-1", "local", listOf(local, s1.copy(health = "degraded")))))
        assertEquals("runner server-1 is full", refused(Placement.pick(null, null, "server-1", listOf(local, s1.copy(freeSlots = 0)))))
        assertEquals("runner local is full", refused(Placement.pick("local", null, "local", listOf(local.copy(freeSlots = 0)))))
    }

    @Test fun anUnknownNameIsRefused() {
        assertEquals("runner 'ghost' is not registered", refused(Placement.pick("ghost", null, "local", listOf(local))))
        assertEquals("runner 'ghost' is not registered", refused(Placement.pick(null, null, "ghost", listOf(local))))
    }
}
