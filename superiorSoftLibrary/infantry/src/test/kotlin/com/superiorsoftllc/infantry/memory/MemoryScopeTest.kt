// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.memory

import com.superiorsoftllc.infantry.context.ContextInput
import com.superiorsoftllc.infantry.context.RoleNotesSource
import com.superiorsoftllc.infantry.context.TicketContextComposer
import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.settings.ProjectSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NOTES-1: role notes (persist with the role) and session notes (die with the session), over the real SQLite file.
 * One test per lifecycle rule: session removal / kill purges only that session's notes, role notes survive
 * session removal and a daemon restart, a role with no sessions keeps its notes, deleting a role purges them (with a
 * typed confirmation), plus the scope-isolation and who-may-write-which-role rules.
 */
/** NOTES-1 (core half; the store and handler rules moved to Infantry_Notes with DP-7f): session-ended listeners and the start context's role-notes section. */
class MemoryScopeTest {
    private val project = "project-1"

    private fun manager() =
        SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50)

    @Test
    fun sessionEndedListenersHearAKillAndARemovalAndCanBeClosed() {
        val manager = manager()
        val heard = mutableListOf<String>()
        val handle = manager.addSessionEndedListener { heard += it }
        val a = manager.startNew("")
        manager.kill(a.id, force = true)
        manager.delete(a.id)
        assertTrue(a.id in heard, "a listener is told when a session ends")
        val before = heard.size
        handle.close()
        val b = manager.startNew("")
        manager.kill(b.id, force = true)
        assertEquals(before, heard.size, "a closed listener hears nothing more")
    }

    /** TURNS-SAVED-1: a kill ends the process but keeps the record (the next mail resumes it); only a delete tells the deleted listeners. */
    @Test
    fun sessionDeletedListenersHearOnlyADeleteNeverAKill() {
        val manager = manager()
        val deleted = mutableListOf<String>()
        val handle = manager.addSessionDeletedListener { deleted += it }
        val a = manager.startNew("")
        manager.kill(a.id, force = true)
        assertTrue(deleted.isEmpty(), "a kill is not a deletion: the session can be resumed, so its per-session state stays")
        manager.delete(a.id)
        assertEquals(listOf(a.id), deleted, "a delete tells the listener once")
        handle.close()
        val b = manager.startNew("")
        manager.kill(b.id, force = true); manager.delete(b.id)
        assertEquals(listOf(a.id), deleted, "a closed listener hears nothing more")
    }

    @Test
    fun aStartListsTheRolesNotesAndOmitsTheSectionWhenThereAreNone() {
        val settings = object : ProjectSettings {
            override fun all() = kotlinx.serialization.json.JsonObject(emptyMap())
            override fun set(key: String, value: kotlinx.serialization.json.JsonElement) = error("unused")
            override fun includeLinkedTasksContext(): Boolean? = null
        }
        fun compose(notes: List<Pair<String, String>>) = TicketContextComposer(
            settings,
            roleNotes = RoleNotesSource { p, r -> if (p == project && r == "qa") notes else emptyList() },
        ).compose(
            ContextInput(instructions = null, title = "t", description = "d", links = emptyList(), includeLinkedOverride = null, projectId = project, role = "qa"),
        )
        val with = compose(listOf("gate-rule" to "run the gate once"))
        assertTrue(with.sources.any { it.source == "roleNotes" })
        assertTrue(with.prompt.contains("- gate-rule: run the gate once"))
        assertTrue(with.prompt.contains("deleted when this session is removed"))
        assertFalse(compose(emptyList()).sources.any { it.source == "roleNotes" })
    }
}
