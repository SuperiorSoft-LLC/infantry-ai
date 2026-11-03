// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.memory.*

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MEM-2: a memory note outlives the daemon that saved it.
 *
 * A daemon restart is, from the memory store's side, a brand-new `MemoryStore` over the same database
 * file the old one was using. This test does exactly that, the way
 * [com.superiorsoftllc.infantry.mvp.WorkspaceStoreRestartTest] does for workspaces: save through one
 * store, CLOSE the handle the way the daemon does when it stops, reopen the very same path, build a
 * new memory store over it, and prove both the project index and the note itself -- body included --
 * come back.
 *
 * The in-memory default would lose the note here, because a new store starts empty: the claim is that
 * the DURABLE surface (the SQLite file) carries it across the restart.
 */
class MemoryStoreRestartTest {

    /** A temp directory holding the SQLite file a daemon would keep its state in. */
    private fun stateDir() = Files.createTempDirectory("infantry-mem2-").toFile()

    @Test
    fun memoryNotesSurviveRestart() {
        val state = stateDir()
        try {
            val dbPath = File(state, "infantry.db").absolutePath

            val first = NotesTestDb.openHandle(dbPath)
            val saved = MemoryStore(
                persistence = StorageMemoryPersistence(first.database),
                idFactory = { "note-1" },
            ).save(
                projectId = "project-1",
                name = "auth-flow",
                description = "Contains the login steps",
                body = "The login steps are: open /login, POST the credentials, expect a session cookie.",
            )
            first.close()

            // The restart: a new handle on the same file, a new memory store over it.
            val second = NotesTestDb.openHandle(dbPath)
            try {
                val reopened = MemoryStore(persistence = StorageMemoryPersistence(second.database))

                assertEquals(
                    listOf(saved.copy(body = "")),
                    reopened.index("project-1"),
                    "the note saved before the restart is listed in its project index after it",
                )
                assertEquals(
                    saved,
                    reopened.get("project-1", "auth-flow"),
                    "the note comes back whole after the restart, body included",
                )
            } finally {
                second.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }
}
