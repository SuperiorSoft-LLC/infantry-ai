// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.session.InMemorySessionInstances
import com.superiorsoftllc.infantry.session.RoleInstance
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
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
        assertTrue("infantry memory index|show|search|save --scope role|session" in section)
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
