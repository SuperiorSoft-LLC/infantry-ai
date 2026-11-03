// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.epics

import com.superiorsoftllc.infantry.db.InfantryDatabaseFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EPIC-3: an epic, and the ordered list of the stories inside it, OUTLIVE the store.
 *
 * A daemon restart is, from the store's side, a brand-new [EpicStore] over the same database file
 * the old one was using. This test therefore does exactly that: open the file, save an epic and
 * append story ids through one store, CLOSE the handle the way the daemon does when it stops,
 * reopen the very same path, and prove the second store answers `list(projectId)` with the epic,
 * its `storyIds` unchanged, and its creation order kept.
 *
 * The in-memory default loses all of it -- that is the defect this slice closes, and the store's
 * own rules (save / list / append) stay covered by `EpicStoreTest`.
 */
class EpicStoreRestartTest {

    /** A temp directory holding the SQLite file a daemon would keep its state in. */
    private fun stateDir() = Files.createTempDirectory("infantry-epic3-").toFile()

    @Test
    fun epicsAndTheirStoryIdsSurviveRestart() {
        val state = stateDir()
        try {
            val dbPath = File(state, "infantry.db").absolutePath

            val first = InfantryDatabaseFactory.open(dbPath)
            val firstStore = EpicStore(persistence = SqlEpicPersistence(first.database))
            val mvp = firstStore.save(projectId = "project-social-machine", name = "MVP")
            firstStore.appendStoryId(mvp.id, "story-first")
            firstStore.appendStoryId(mvp.id, "story-second")
            val postMvp = firstStore.save(projectId = "project-social-machine", name = "Post MVP")
            first.close()

            // The restart: a new handle on the same file, a new store over it.
            val second = InfantryDatabaseFactory.open(dbPath)
            try {
                val reopened = EpicStore(persistence = SqlEpicPersistence(second.database))
                assertEquals(
                    listOf(
                        mvp.copy(storyIds = listOf("story-first", "story-second")),
                        postMvp,
                    ),
                    reopened.list("project-social-machine"),
                    "an epic and its ordered storyIds outlive the store, in creation order",
                )
            } finally {
                second.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }
}
