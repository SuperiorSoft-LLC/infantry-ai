package com.superiorsoftllc.agenthq.mvp

import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero, slice A2: the PROJECT store's PERSISTENCE.
 *
 * A daemon restart is, from the store's side, a brand-new `ProjectStore` over the same database
 * file the old one was using. These tests therefore do exactly that: open the file, save through
 * one store, CLOSE the handle the way the daemon does when it stops, reopen the very same path,
 * and prove the second store answers with what the first one wrote.
 *
 * The store's own operations (create / read / list / refusals) are covered by `ProjectStoreTest`
 * and stay in memory; this suite only asks whether what those operations wrote OUTLIVES the
 * store. A project's id is the MVP's join key (a ticket and a story name their project by it), so
 * an id that changes across a restart silently orphans every row that points at it.
 *
 * The in-memory store remains the default; the persistence surface is injected, so a caller that
 * wants durability (the daemon) passes a durable one and everyone else keeps A1's behaviour.
 */
class ProjectStoreRestartTest {

    /** A temp directory holding the SQLite file a daemon would keep its state in. */
    private fun stateDir() = Files.createTempDirectory("agenthq-mvp-a2-").toFile()

    @Test
    fun aProjectSurvivesReopeningTheSameStore() {
        val state = stateDir()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath

            val first = AgentHQDatabaseFactory.open(dbPath)
            val created = ProjectStore(persistence = SqlProjectPersistence(first.database))
                .save(ProjectSave(name = "Sprint-Zero MVP", goal = "one story, three ways"))
            first.close()

            // The restart: a new handle on the same file, a new store over it.
            val second = AgentHQDatabaseFactory.open(dbPath)
            try {
                val reopened = ProjectStore(persistence = SqlProjectPersistence(second.database))
                assertEquals(
                    created,
                    reopened.get(created.id),
                    "the same project, with the same stable id, after a restart: ${created.id}",
                )
            } finally {
                second.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }

    @Test
    fun projectsListInCreationOrderAfterARestart() {
        val state = stateDir()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath

            val first = AgentHQDatabaseFactory.open(dbPath)
            val firstStore = ProjectStore(persistence = SqlProjectPersistence(first.database))
            val early = firstStore.save(ProjectSave(name = "MVP 2/11"))
            val late = firstStore.save(ProjectSave(name = "MVP 3/11"))
            first.close()

            val second = AgentHQDatabaseFactory.open(dbPath)
            try {
                val reopened = ProjectStore(persistence = SqlProjectPersistence(second.database))
                assertEquals(
                    listOf(early, late),
                    reopened.list(),
                    "the listing keeps the order the projects were created in",
                )
            } finally {
                second.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }

    @Test
    fun anEditMadeBeforeTheStopIsWhatTheRestartReads() {
        val state = stateDir()
        try {
            val dbPath = File(state, "agenthq.db").absolutePath

            val first = AgentHQDatabaseFactory.open(dbPath)
            val firstStore = ProjectStore(persistence = SqlProjectPersistence(first.database))
            val created = firstStore.save(ProjectSave(name = "Sprint-Zero MVP", goal = "one story, three ways"))
            firstStore.save(ProjectSave(id = created.id, name = "Sprint-Zero MVP, measured"))
            first.close()

            val second = AgentHQDatabaseFactory.open(dbPath)
            try {
                val afterRestart = ProjectStore(persistence = SqlProjectPersistence(second.database))
                    .get(created.id)
                assertEquals("Sprint-Zero MVP, measured", afterRestart.name)
                assertEquals("one story, three ways", afterRestart.goal, "a field the edit left out stays as it was")
            } finally {
                second.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }

    @Test
    fun aStoreWithoutPersistenceKeepsTheInMemoryBehaviour() {
        val store = ProjectStore(idFactory = { "project-1" })
        val created = store.save(ProjectSave(name = "Sprint-Zero MVP", goal = "one story, three ways"))

        assertEquals("project-1", created.id)
        assertEquals(created, store.get("project-1"))
        assertTrue(store.list() == listOf(created), "the default store lists what it holds: ${store.list()}")
    }
}
