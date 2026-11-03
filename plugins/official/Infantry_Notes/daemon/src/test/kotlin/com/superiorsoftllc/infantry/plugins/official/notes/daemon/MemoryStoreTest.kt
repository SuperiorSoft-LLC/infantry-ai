package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.memory.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MEM-1: a saved memory note is listed in its project's index.
 *
 * The daemon puts a project's note index into every agent's brief, so the one thing that has to hold
 * is that a note saved into a project shows up in that project's index -- carrying the note's name and
 * description, and NOT its body (the body is fetched later, because it has no business in a brief).
 */
class MemoryStoreTest {

    @Test
    fun memoryNoteSaveIsListedInProjectIndex() {
        val store = MemoryStore(idFactory = { "note-1" })

        store.save(
            projectId = "project-1",
            name = "auth-flow",
            description = "Contains the login steps",
            body = "The login steps are: open /login, POST the credentials, expect a session cookie.",
        )

        val index = store.index("project-1")

        assertEquals(1, index.size, "the saved note is listed in its project's index")
        val entry = index.single()
        assertEquals("auth-flow", entry.name, "the index entry carries the note name")
        assertEquals("Contains the login steps", entry.description, "the index entry carries the description")
        assertTrue(entry.body.isEmpty(), "the index carries the name and description, not the body: '${entry.body}'")
    }
}
