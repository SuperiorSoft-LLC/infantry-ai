// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.db.InfantryDatabaseFactory
import com.superiorsoftllc.infantry.protocol.ProjectSave
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CP-2 slice (a): a project's DIRECTORY -- the first project-scoped setting.
 *
 * A project used to be a name and a goal. It now also records the directory it lives in, which the
 * daemon's git service (slice b) is rooted at. The rules pinned here are the store's own:
 *  - a create or an edit can set it, an edit that leaves it out keeps it, an empty one clears it;
 *  - it must be an ABSOLUTE path to a directory that EXISTS, because a relative path would resolve
 *    against whatever the daemon's working directory happened to be, and a missing one would make
 *    every git read answer "unknown" for a reason the user could have been told at save time;
 *  - a refused save writes nothing; an accepted one survives a restart.
 */
class ProjectDirectoryTest {

    private fun store(vararg ids: String): ProjectStore {
        val queue = ArrayDeque(listOf(*ids))
        return ProjectStore(idFactory = { queue.removeFirstOrNull() ?: error("no more ids to mint") })
    }

    private fun tempDir(): File = Files.createTempDirectory("infantry-cp2-dir-").toFile()

    @Test
    fun aProjectIsCreatedWithItsDirectoryAndReadsItBack() {
        val dir = tempDir()
        try {
            val store = store("project-1")

            val created = store.save(ProjectSave(name = "Social Machine", directory = dir.absolutePath))

            assertEquals(dir.absolutePath, created.directory, "the create answers with the directory it was given")
            assertEquals(dir.absolutePath, store.get("project-1").directory, "and a read returns the same")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aProjectWithoutADirectoryHasAnEmptyOne() {
        val created = store("project-1").save(ProjectSave(name = "No directory yet"))

        assertEquals("", created.directory, "unset is the empty string, never null")
    }

    @Test
    fun anEditThatOmitsTheDirectoryKeepsItAndAnEmptyOneClearsIt() {
        val dir = tempDir()
        try {
            val store = store("project-1")
            store.save(ProjectSave(name = "P", directory = dir.absolutePath))

            val renamed = store.save(ProjectSave(id = "project-1", name = "P renamed"))
            assertEquals(dir.absolutePath, renamed.directory, "an edit that says nothing about the directory keeps it")
            assertEquals("P renamed", renamed.name)

            val cleared = store.save(ProjectSave(id = "project-1", directory = ""))
            assertEquals("", cleared.directory, "an explicit empty directory clears it")
            assertEquals("P renamed", cleared.name, "and the rest of the project is untouched")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRelativeDirectoryIsRefusedAndNothingIsWritten() {
        val store = store("project-1")

        val refusal = assertFailsWith<IllegalArgumentException> {
            store.save(ProjectSave(name = "P", directory = "relative/path"))
        }

        assertTrue(refusal.message.orEmpty().contains("absolute"), "the refusal names the rule: ${refusal.message}")
        assertTrue(store.list().isEmpty(), "a refused create leaves no project behind: ${store.list()}")
    }

    @Test
    fun aDirectoryThatDoesNotExistIsRefusedAndAnEditLeavesTheOldOneInPlace() {
        val dir = tempDir()
        try {
            val store = store("project-1")
            store.save(ProjectSave(name = "P", directory = dir.absolutePath))
            val missing = File(dir, "nope").absolutePath

            val refusal = assertFailsWith<IllegalArgumentException> {
                store.save(ProjectSave(id = "project-1", directory = missing))
            }

            assertTrue(refusal.message.orEmpty().contains("not a directory"), "names the problem: ${refusal.message}")
            assertEquals(dir.absolutePath, store.get("project-1").directory, "the old directory is still in place")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFileIsNotADirectory() {
        val dir = tempDir()
        try {
            val file = File(dir, "a.txt").apply { writeText("x") }

            assertFailsWith<IllegalArgumentException> {
                store("project-1").save(ProjectSave(name = "P", directory = file.absolutePath))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theDirectorySurvivesARestart() {
        val dir = tempDir()
        val state = Files.createTempDirectory("infantry-cp2-state-").toFile()
        try {
            val dbPath = File(state, "infantry.db").absolutePath

            val first = InfantryDatabaseFactory.open(dbPath)
            val created = ProjectStore(persistence = SqlProjectPersistence(first.database))
                .save(ProjectSave(name = "P", directory = dir.absolutePath))
            first.close()

            val second = InfantryDatabaseFactory.open(dbPath)
            try {
                val reopened = ProjectStore(persistence = SqlProjectPersistence(second.database))
                assertEquals(dir.absolutePath, reopened.get(created.id).directory, "directory read back after a restart")
                // And an edit on the reopened store persists too (the update half of the upsert).
                reopened.save(ProjectSave(id = created.id, directory = ""))
            } finally {
                second.close()
            }

            val third = InfantryDatabaseFactory.open(dbPath)
            try {
                assertEquals(
                    "",
                    ProjectStore(persistence = SqlProjectPersistence(third.database)).get(created.id).directory,
                    "a cleared directory stays cleared after another restart",
                )
            } finally {
                third.close()
            }
        } finally {
            dir.deleteRecursively()
            runCatching { state.deleteRecursively() }
        }
    }
}
