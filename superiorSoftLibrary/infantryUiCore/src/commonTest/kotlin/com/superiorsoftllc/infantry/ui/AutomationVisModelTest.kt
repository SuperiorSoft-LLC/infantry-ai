package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AgentGroup
import com.superiorsoftllc.infantry.ui.model.AgentView
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ChatItem
import com.superiorsoftllc.infantry.ui.model.TimelineItem
import com.superiorsoftllc.infantry.ui.model.automationStats
import com.superiorsoftllc.infantry.ui.model.wakeLine
import com.superiorsoftllc.infantry.ui.model.wakeReasons
import com.superiorsoftllc.infantry.ui.model.withContributions
import com.superiorsoftllc.infantry.ui.model.withTimeline
import com.superiorsoftllc.infantry.ui.protocol.AutomationView
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCRIPT-VIS-1c: the automation grouping, the stats item (turns saved, wake reasons), and the chat rows. */
class AutomationVisModelTest {
    private fun agent(id: String, automation: AutomationView? = null) = AgentView(
        session = SessionRecord(id = id, provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE, providerSessionId = "p", automation = automation),
        role = "pm",
    )
    private val stats = TimelineItem("stats-a", "a", 5, "automation-stats", "AUTOMATION stats", "turns saved 2 - wakes 1",
        meta = mapOf("turnsSaved" to "2", "wakes" to "1", "wakesByReason" to "mail:3,owner:1", "lastWake" to "owner"))
    private val run = TimelineItem("run-1", "a", 3, "automation", "AUTOMATION - pong", "handled", details = listOf("trigger: m"))

    @Test fun anIdleSessionWithAnAutomationMarkIsListedUnderWorking() {
        val a = agent("a", AutomationView("pong", "m", 1)); val b = agent("b")
        val s = AppState(agents = mapOf("a" to a, "b" to b))
        assertEquals(AgentGroup.WORKING, s.agentGroup(a))
        assertEquals(AgentGroup.INACTIVE, s.agentGroup(b))
    }

    @Test fun statsStayOutOfTheChatRowsButFeedThePanelAndTheWakeLine() {
        val s = AppState().withTimeline(listOf(run, stats))
        assertEquals(stats, s.automationStats("a"))
        assertEquals("woken by owner", s.wakeLine("a"))
        assertEquals("mail 3, owner 1", stats.wakeReasons())
        val rows = withContributions(emptyList(), s.timeline["a"].orEmpty())
        assertEquals(listOf("run-1"), rows.filterIsInstance<ChatItem.Contribution>().map { it.item.id })
    }

    @Test fun noStatsMeansNoPanelAndNoWakeLine() {
        val s = AppState().withTimeline(listOf(run))
        assertNull(s.automationStats("a")); assertNull(s.wakeLine("a")); assertTrue(withContributions(emptyList(), emptyList()).isEmpty())
    }

    @Test fun aNewStatsItemReplacesTheOldOne() {
        val s = AppState().withTimeline(listOf(stats)).withTimeline(listOf(stats.copy(at = 9, meta = stats.meta + ("turnsSaved" to "3"))))
        assertEquals("3", s.automationStats("a")!!.meta["turnsSaved"]); assertEquals(1, s.timeline["a"]!!.size)
    }
}
