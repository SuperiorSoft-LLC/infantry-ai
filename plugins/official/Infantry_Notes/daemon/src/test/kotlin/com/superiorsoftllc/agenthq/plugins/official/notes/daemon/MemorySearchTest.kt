package com.superiorsoftllc.agenthq.plugins.official.notes.daemon

import com.superiorsoftllc.agenthq.memory.*

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MEM-3: a memory note is found by the text of its BODY, and only inside its own project.
 *
 * The database is the source of truth precisely so this is cheap: a search runs as a query over the
 * `memory_notes` rows rather than loading and scanning every project's notes. This test runs at the
 * daemon level, over the same SQLite file the daemon keeps its state in (via
 * [StorageMemoryPersistence]), so it exercises the real query and not the in-memory default.
 *
 * The one claim: given two notes in project P -- one whose body mentions "refresh token rotation" --
 * searching P for "rotation" returns exactly that note, as a result carrying its name, description
 * and a body snippet, while the same search in another project returns nothing: a note never leaks
 * across the project boundary.
 *
 * Ranking, empty queries and description/name-only matches are deliberately NOT asserted here: they
 * are follow-up tickets, not part of MEM-3.
 */
class MemorySearchTest {

    /** A temp directory holding the SQLite file a daemon would keep its state in. */
    private fun stateDir() = Files.createTempDirectory("agenthq-mem3-").toFile()

    @Test
    fun memorySearchEscapesLikeWildcards() {
        val state = stateDir()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath
            val handle = NotesTestDb.openHandle(dbPath)
            try {
                val store = MemoryStore(
                    persistence = StorageMemoryPersistence(handle.database),
                    idFactory = { "note-" + System.nanoTime() },
                )

                store.save(
                    projectId = "project-1",
                    name = "a",
                    description = "",
                    body = "100% done",
                )
                store.save(
                    projectId = "project-1",
                    name = "b",
                    description = "",
                    body = "plain",
                )

                val percentHits = store.search("project-1", "%")

                assertEquals(1, percentHits.size, "a '%' is a literal character, not a LIKE wildcard")
                assertEquals("a", percentHits.single().name, "only the note containing a literal '%' is found")

                assertTrue(
                    store.search("project-1", "_").isEmpty(),
                    "an '_' is a literal character, not a single-character LIKE wildcard",
                )
            } finally {
                handle.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }

    @Test
    fun memorySearchFindsNoteByBodyText() {
        val state = stateDir()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath
            val handle = NotesTestDb.openHandle(dbPath)
            try {
                val store = MemoryStore(
                    persistence = StorageMemoryPersistence(handle.database),
                    idFactory = { "note-" + System.nanoTime() },
                )

                store.save(
                    projectId = "project-1",
                    name = "auth-flow",
                    description = "Contains the login steps",
                    body = "The login steps end with a refresh token rotation every 30 minutes.",
                )
                store.save(
                    projectId = "project-1",
                    name = "billing-flow",
                    description = "Contains the invoice steps",
                    body = "Invoices are charged once a month against the stored card.",
                )
                // A second project, whose notes must never answer a search of project-1.
                store.save(
                    projectId = "project-2",
                    name = "other-flow",
                    description = "Contains unrelated steps",
                    body = "Nothing here matches the term.",
                )

                val hits = store.search("project-1", "rotation")

                assertEquals(1, hits.size, "only the note whose body mentions 'rotation' is found")
                val hit = hits.single()
                assertEquals("auth-flow", hit.name, "the hit carries the note name")
                assertEquals("Contains the login steps", hit.description, "the hit carries the description")
                assertTrue(
                    hit.snippet.contains("rotation"),
                    "the hit carries a body snippet around the match: '${hit.snippet}'",
                )

                assertTrue(
                    store.search("project-2", "rotation").isEmpty(),
                    "the term found in project-1 does not leak into project-2's search",
                )
            } finally {
                handle.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }
}
