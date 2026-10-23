package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.session.InMemorySessionInstances
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROLE-NOTES-1: role notes reach every first turn, and `restart` reboots a session in place. */
class RoleNotesFirstTurnTest {
    private fun counter(notes: List<Pair<String, String>>) = object : KnowledgeCounter {
        override fun counts(projectId: String) = KnowledgeCounts(1, 0, 0)
        override fun roleNotes(projectId: String, role: String) = if (role == "pm") notes else emptyList()
    }

    @Test
    fun aRoleSessionsKnowledgeSectionCarriesItsRoleNotesAndTheScopeHowTo() {
        val section = counter(listOf("merge-rule" to "never merge red")).section("p1", "pm")!!
        assertTrue("PROJECT KNOWLEDGE" in section)
        assertTrue("ROLE NOTES (scope role: notes for the 'pm' role" in section)
        assertTrue("- merge-rule: never merge red" in section)
        assertTrue("agenthq memory index|show|search|save --scope role|session" in section)
    }

    @Test
    fun aRoleWithNoNotesStillGetsTheHowTo() {
        val section = counter(emptyList()).section("p1", "qa")!!
        assertTrue("ROLE NOTES: none yet for the 'qa' role" in section)
        assertTrue("--scope role|session" in section)
    }

    @Test
    fun noRoleOrNoProjectIsExactlyTheKnowledgeSection() {
        val c = counter(listOf("a" to "b"))
        assertEquals(c.section("p1"), c.section("p1", null))
        assertNull(c.section(null, "pm"))
    }

    @Test
    fun restartMakesTheSessionNeverRunWithTheSameIdentityAndRefreshesItsInstance() {
        val instances = InMemorySessionInstances()
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, instances = instances)
        var persona = "old"
        manager.instanceFor = { agentId, role -> RoleInstance(agentId, role, persona, emptyList()) }
        val s = manager.startNew("", agentId = "a1", role = "pm")
        persona = "new"

        val after = manager.restart(s.id)

        assertEquals(s.id, after.id)
        assertEquals("pm", after.role)
        assertEquals(SessionState.IDLE, after.state)
        assertTrue(after.neverRun)
        assertEquals("new", instances.get(s.id)?.instructions)
    }

    @Test
    fun restartRefusesAnUnknownSession() {
        val manager = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50)
        assertFailsWith<IllegalArgumentException> { manager.restart("nope") }
    }
}
