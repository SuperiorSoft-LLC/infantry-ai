package com.superiorsoftllc.agenthq.memory

import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.RoleNotesSource
import com.superiorsoftllc.agenthq.context.TicketContextComposer
import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.settings.ProjectSettings
import java.io.File
import java.nio.file.Files
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
class MemoryScopeTest {
    private val project = "project-1"

    private fun withDb(block: (open: () -> MemoryStore) -> Unit) {
        val state = Files.createTempDirectory("agenthq-notes1-").toFile()
        val handles = mutableListOf<AutoCloseable>()
        try {
            val path = File(state, "agenthq.db").absolutePath
            // Each call is a "daemon start": a new handle and a new store over the same file.
            val open = {
                val handle = AgentHQDatabaseFactory.open(path)
                handles += handle
                MemoryStore(persistence = SqlMemoryPersistence(handle.database))
            }
            block(open)
        } finally {
            handles.forEach { runCatching { it.close() } }
            state.deleteRecursively()
        }
    }

    private fun MemoryStore.seed() {
        save(project, "rule", "shared", "project body")
        save(project, "rule", "pm only", "pm body", scope = SCOPE_ROLE, ownerId = "pm")
        save(project, "rule", "qa only", "qa body", scope = SCOPE_ROLE, ownerId = "qa")
        save(project, "rule", "mine", "s1 body", scope = SCOPE_SESSION, ownerId = "s1")
        save(project, "rule", "other", "s2 body", scope = SCOPE_SESSION, ownerId = "s2")
    }

    @Test
    fun theSameNameLivesIndependentlyInEveryScope() = withDb { open ->
        val store = open()
        store.seed()
        assertEquals("project body", store.get(project, "rule")!!.body)
        assertEquals("pm body", store.get(project, "rule", SCOPE_ROLE, "pm")!!.body)
        assertEquals("qa body", store.get(project, "rule", SCOPE_ROLE, "qa")!!.body)
        assertEquals("s1 body", store.get(project, "rule", SCOPE_SESSION, "s1")!!.body)
        assertEquals(listOf("shared"), store.index(project).map { it.description })
        assertEquals(listOf("pm only"), store.index(project, SCOPE_ROLE, "pm").map { it.description })
        assertTrue(store.search(project, "body").size == 1, "a project search never returns role or session notes")
        assertEquals(1, store.search(project, "pm body", SCOPE_ROLE, "pm").size)
        assertEquals(0, store.search(project, "pm body", SCOPE_ROLE, "qa").size)
    }

    @Test
    fun aScopeNeedsItsOwnerAndAProjectNoteHasNone() = withDb { open ->
        val store = open()
        assertFailsWith<IllegalArgumentException> { store.save(project, "n", "d", "b", scope = SCOPE_ROLE) }
        assertFailsWith<IllegalArgumentException> { store.save(project, "n", "d", "b", scope = SCOPE_SESSION, ownerId = "") }
        assertFailsWith<IllegalArgumentException> { store.save(project, "n", "d", "b", scope = "global") }
        assertFailsWith<IllegalArgumentException> { store.save(project, "n", "d", "b", ownerId = "x") }
    }

    @Test
    fun purgingASessionDeletesOnlyThatSessionsNotes() = withDb { open ->
        val store = open()
        store.seed()
        assertEquals(1, store.purgeSession("s1"))
        assertNull(store.get(project, "rule", SCOPE_SESSION, "s1"))
        assertNotNull(store.get(project, "rule", SCOPE_SESSION, "s2"))
        assertNotNull(store.get(project, "rule", SCOPE_ROLE, "pm"))
        assertNotNull(store.get(project, "rule"))
        // Durable: a restart does not bring the purged rows back.
        assertNull(open().get(project, "rule", SCOPE_SESSION, "s1"))
    }

    @Test
    fun roleAndProjectNotesSurviveARestartAndARoleWithNoSessionsKeepsItsNotes() = withDb { open ->
        open().seed()
        val restarted = open()
        assertEquals("pm body", restarted.get(project, "rule", SCOPE_ROLE, "pm")!!.body)
        assertEquals("qa body", restarted.get(project, "rule", SCOPE_ROLE, "qa")!!.body)
        assertEquals("project body", restarted.get(project, "rule")!!.body)
        // No session exists for either role anywhere in this test: the notes are keyed on the role, not on a session.
        restarted.purgeSession("s1")
        restarted.purgeSession("s2")
        assertEquals("pm body", open().get(project, "rule", SCOPE_ROLE, "pm")!!.body)
    }

    @Test
    fun deletingARolePurgesItsNotesInEveryProjectAndNothingElse() = withDb { open ->
        val store = open()
        store.seed()
        store.save("project-2", "rule", "pm elsewhere", "x", scope = SCOPE_ROLE, ownerId = "pm")
        assertEquals(2, store.purgeRole("pm"))
        val restarted = open()
        assertNull(restarted.get(project, "rule", SCOPE_ROLE, "pm"))
        assertNull(restarted.get("project-2", "rule", SCOPE_ROLE, "pm"))
        assertNotNull(restarted.get(project, "rule", SCOPE_ROLE, "qa"))
        assertNotNull(restarted.get(project, "rule", SCOPE_SESSION, "s1"))
    }

    @Test
    fun deleteRemovesOneNote() = withDb { open ->
        val store = open()
        store.seed()
        assertTrue(store.delete(project, "rule", SCOPE_ROLE, "qa"))
        assertFalse(store.delete(project, "rule", SCOPE_ROLE, "qa"))
        assertNull(open().get(project, "rule", SCOPE_ROLE, "qa"))
        assertNotNull(open().get(project, "rule", SCOPE_ROLE, "pm"))
    }

    @Test
    fun appendAndEditStayInsideTheirScope() = withDb { open ->
        val store = open()
        store.seed()
        store.append(project, "rule", "more", scope = SCOPE_ROLE, ownerId = "pm")
        assertEquals("pm body\n\nmore", store.get(project, "rule", SCOPE_ROLE, "pm")!!.body)
        assertEquals("project body", store.get(project, "rule")!!.body)
        assertFailsWith<MemoryRefusal> { store.append(project, "absent", "x", scope = SCOPE_SESSION, ownerId = "s1") }
    }

    // --- real session removal / kill ------------------------------------------------------------------------------

    private fun manager(store: MemoryStore) =
        SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50)
            .also { it.onSessionEnded = { id -> store.purgeSession(id) } }

    @Test
    fun removingASessionPurgesItsSessionNotesAndNoOthers() {
        val store = MemoryStore()
        val manager = manager(store)
        val a = manager.startNew("")
        val b = manager.startNew("")
        store.save(project, "n", "d", "a", scope = SCOPE_SESSION, ownerId = a.id)
        store.save(project, "n", "d", "b", scope = SCOPE_SESSION, ownerId = b.id)
        store.save(project, "n", "d", "r", scope = SCOPE_ROLE, ownerId = "backend")
        manager.kill(a.id, force = true) // a kill purges too ...
        assertNull(store.get(project, "n", SCOPE_SESSION, a.id))
        manager.delete(a.id) // ... and so does the removal of an already purged session (idempotent)
        assertNull(store.get(project, "n", SCOPE_SESSION, a.id))
        assertNotNull(store.get(project, "n", SCOPE_SESSION, b.id))
        assertNotNull(store.get(project, "n", SCOPE_ROLE, "backend"), "a role note survives the removal of a session of that role")
        manager.kill(b.id, force = true)
        manager.delete(b.id)
        assertNull(store.get(project, "n", SCOPE_SESSION, b.id))
        assertNotNull(store.get(project, "n", SCOPE_ROLE, "backend"))
    }

    @Test
    fun aSavedOrDeletedRoleNoteIsAnnouncedButProjectAndSessionNotesAreNot() {
        val seen = mutableListOf<String>()
        val h = MemoryHandler(
            MemoryStore(),
            caller = { id -> if (id == "pm-session") MemoryCaller(id, "pm") else null },
            onRoleNoteChanged = { p, role, name, deleted -> seen += "$p/$role/$name/$deleted" },
        )
        save(h, MemorySave(project, "r1", "d", "b", scope = "role", sessionId = "pm-session"))
        save(h, MemorySave(project, "s1", "d", "b", scope = "session", sessionId = "pm-session"))
        save(h, MemorySave(project, "p1", "d", "b"))
        assertEquals(listOf("$project/pm/r1/false"), seen)
        h.answer(Envelope(Envelope.MEMORY_DELETE, json.encodeToString(MemoryDelete.serializer(), MemoryDelete(project, "r1", "role", "pm-session", null))))
        assertEquals(listOf("$project/pm/r1/false", "$project/pm/r1/true"), seen)
    }

    // --- wire: who may address what ---------------------------------------------------------------------------------

    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

    private fun handler(store: MemoryStore) = MemoryHandler(
        store,
        caller = { id ->
            when (id) {
                "pm-session" -> MemoryCaller(id, "pm")
                "qa-session" -> MemoryCaller(id, "qa")
                else -> null
            }
        },
    )

    private fun save(h: MemoryHandler, req: MemorySave): Envelope =
        h.answer(Envelope(Envelope.MEMORY_SAVE, json.encodeToString(MemorySave.serializer(), req)))!!

    @Test
    fun anAgentWritesItsOwnRoleAndSessionButOnlyThePmOrTheOwnerWritesAnotherRole() {
        val store = MemoryStore()
        val h = handler(store)
        fun req(session: String?, scope: String, role: String? = null) =
            MemorySave(project, "n", "d", "b", scope = scope, sessionId = session, role = role)

        assertEquals(Envelope.MEMORY_NOTE, save(h, req("qa-session", "role")).type, "own role, taken from the calling session")
        assertEquals("qa", store.get(project, "n", SCOPE_ROLE, "qa")!!.ownerId)
        assertEquals(Envelope.MEMORY_NOTE, save(h, req("qa-session", "session")).type)
        assertNotNull(store.get(project, "n", SCOPE_SESSION, "qa-session"))

        val refused = save(h, req("qa-session", "role", role = "pm"))
        assertEquals(Envelope.ERROR, refused.type)
        assertTrue(refused.data.contains("only the pm and the owner"), refused.data)
        assertNull(store.get(project, "n", SCOPE_ROLE, "pm"))

        assertEquals(Envelope.MEMORY_NOTE, save(h, req("pm-session", "role", role = "qa")).type, "the pm may write any role")
        assertEquals(Envelope.MEMORY_NOTE, save(h, req(null, "role", role = "devops")).type, "the owner (no session) may write any role")
        assertEquals(Envelope.ERROR, save(h, req(null, "session")).type, "a session note needs a calling session")
        assertEquals(Envelope.ERROR, save(h, req("ghost", "role")).type, "an unknown session is refused")
    }

    @Test
    fun purgeRoleNeedsTheTypedConfirmation() {
        val store = MemoryStore()
        store.save(project, "n", "d", "b", scope = SCOPE_ROLE, ownerId = "qa")
        val h = handler(store)
        fun purge(confirm: String) = h.answer(
            Envelope(Envelope.MEMORY_PURGE_ROLE, json.encodeToString(MemoryPurgeRole.serializer(), MemoryPurgeRole("qa", confirm))),
        )!!
        assertEquals(Envelope.ERROR, purge("yes").type)
        assertNotNull(store.get(project, "n", SCOPE_ROLE, "qa"))
        assertEquals(Envelope.MEMORY_NOTES, purge("qa").type)
        assertNull(store.get(project, "n", SCOPE_ROLE, "qa"))
    }

    // --- start context ----------------------------------------------------------------------------------------------

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
