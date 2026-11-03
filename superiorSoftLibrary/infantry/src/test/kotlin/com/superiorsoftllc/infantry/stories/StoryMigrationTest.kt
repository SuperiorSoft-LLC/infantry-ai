// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.stories

import com.superiorsoftllc.infantry.backlog.TicketBoard
import com.superiorsoftllc.infantry.epics.Epic
import com.superiorsoftllc.infantry.epics.SqlEpicPersistence
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.TicketStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E1-a: `stories.json` becomes board tickets at startup, once. Each "start" here is a fresh store + board over the
 * same database file, which is what a daemon restart is for this step.
 */
class StoryMigrationTest {

    private val dir: File = Files.createTempDirectory("infantry-story-migration").toFile()
    private val dbPath = File(dir, "infantry.db").absolutePath
    private val storiesFile = File(dir, "stories.json")
    private val migratedFile = File(dir, "stories.json.migrated")
    private val opened = mutableListOf<SqlInfantryStore>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private val threeStories = """
        {
          "version": 1,
          "stories": [
            { "id": "story-a1", "projectId": "proj-1", "title": "First story", "status": "todo" },
            { "id": "story-b2", "projectId": "proj-1", "title": "Second story", "status": "doing" },
            { "id": "story-c3", "projectId": "proj-2", "title": "Third story", "status": "done" }
          ]
        }
    """.trimIndent()

    private fun open(): SqlInfantryStore = SqlInfantryStore.open(dbPath).also { opened += it }

    private fun start(afterStory: (String) -> Unit = {}): Pair<TicketBoard, StoryMigration.Result> {
        val store = open()
        val board = TicketBoard(store)
        return board to StoryMigration(storiesFile, board, store.database, afterStory = afterStory).run()
    }

    private fun seedEpics() {
        val persistence = SqlEpicPersistence(open().database)
        persistence.upsert(Epic(id = "epic-one", projectId = "proj-1", name = "One", goal = "", storyIds = listOf("story-a1", "story-b2")))
        persistence.upsert(Epic(id = "epic-two", projectId = "proj-1", name = "Two", goal = "", storyIds = listOf("story-b2", "story-c3")))
    }

    private fun migrated(board: TicketBoard): Map<String, Ticket> =
        board.list().filter { it.description.startsWith("Migrated from story ") }
            .associateBy { it.description.removePrefix("Migrated from story ").substringBefore('\n') }

    @Test
    fun `three stories in two epics become three tickets with the right status and epic, and the file is renamed`() {
        storiesFile.writeText(threeStories)
        seedEpics()
        val preBoard = TicketBoard(open())
        val existing = preBoard.save(TicketSave(title = "Already here")).ticket

        val (board, result) = start()

        assertEquals(3, result.migrated)
        val byStory = migrated(board)
        assertEquals(setOf("story-a1", "story-b2", "story-c3"), byStory.keys)
        assertEquals(TicketStatus.TODO, byStory.getValue("story-a1").status)
        assertEquals(TicketStatus.IN_PROGRESS, byStory.getValue("story-b2").status)
        assertEquals(TicketStatus.DONE, byStory.getValue("story-c3").status)
        assertEquals("epic-one", byStory.getValue("story-a1").epicId)
        assertEquals("epic-one", byStory.getValue("story-b2").epicId)
        assertEquals("epic-two", byStory.getValue("story-c3").epicId)
        assertTrue(byStory.getValue("story-b2").description.contains("Also listed under epic epic-two"))
        assertEquals("First story", byStory.getValue("story-a1").title)
        assertEquals("proj-2", byStory.getValue("story-c3").projectId)
        assertEquals(4, board.list().size)
        assertEquals(existing, board.get(existing.id), "an existing ticket is not modified")
        assertTrue(migratedFile.isFile, "stories.json.migrated exists")
        assertFalse(storiesFile.exists(), "stories.json is gone")
    }

    @Test
    fun `a second start adds no ticket`() {
        storiesFile.writeText(threeStories)
        seedEpics()
        start()
        // Even with the file put back (a story_save flushed it again), already-mapped stories are skipped.
        migratedFile.copyTo(storiesFile)

        val (board, result) = start()

        assertEquals(0, result.migrated)
        assertEquals(3, result.skipped)
        assertEquals(3, board.list().size)
    }

    @Test
    fun `a crash after one of three resumes to exactly three`() {
        storiesFile.writeText(threeStories)
        seedEpics()
        assertFailsWith<IllegalStateException> { start(afterStory = { error("crash after $it") }) }
        assertEquals(1, TicketBoard(open()).list().size)
        assertTrue(storiesFile.isFile, "not renamed before every story is mapped")

        val (board, result) = start()

        assertEquals(2, result.migrated)
        assertEquals(1, result.skipped)
        assertEquals(setOf("story-a1", "story-b2", "story-c3"), migrated(board).keys)
        assertEquals(3, board.list().size)
        assertTrue(migratedFile.isFile)
    }

    @Test
    fun `a corrupt file gives no ticket and is left untouched`() {
        val corrupt = """{ "stories": [ { "id": "story-x", "projectId": "p", "title": "t", "status": "nope" } ] }"""
        storiesFile.writeText(corrupt)

        val (board, result) = start()

        assertTrue(result.corrupt)
        assertEquals(0, board.list().size)
        assertEquals(corrupt, storiesFile.readText())
        assertFalse(migratedFile.exists())

        storiesFile.writeText("{ not json")
        val (board2, result2) = start()
        assertTrue(result2.corrupt)
        assertEquals(0, board2.list().size)
        assertEquals("{ not json", storiesFile.readText())
    }

    @Test
    fun `a missing file is a no-op`() {
        val (board, result) = start()
        assertEquals(StoryMigration.Result(0, 0, renamed = false), result)
        assertEquals(0, board.list().size)
    }
}
