// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.stories

import com.superiorsoftllc.infantry.protocol.Story
import com.superiorsoftllc.infantry.protocol.StoryMove
import com.superiorsoftllc.infantry.protocol.StorySave
import com.superiorsoftllc.infantry.protocol.StoryStatus
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero, slice B leaf B2 (ticket `20ce1d7c`, branch `feature-agenthq-mvp-board`) -- status
 * MOVES and restart PERSISTENCE.
 *
 * What this suite proves on top of B1's ([StoryStoreTest], which stays intact above it):
 *
 * 1. A story walks the WHOLE frozen vocabulary -- `todo` -> `doing` -> `done`, in that order, and
 *    back the other way when the work reopens. Three columns, no partial-order rule was frozen, and
 *    the store never invents one.
 * 2. The column a story is in SURVIVES a restart: reopen the store over the same file and every
 *    story, its project, its title and its selected status are exactly where they were left. That is
 *    the whole point of [JsonStoryStore] -- a board that loses its columns when the daemon restarts
 *    is not a board.
 *
 * Deliberately NOT here, so each behaviour keeps one seam: the board listing (grouping by status,
 * partition order) is B3 (`4d4b74dc`); the wire frames behind this store (`story_save` /
 * `story_get` / `story_move` / `story_list`) are wired by the integrating slice, so
 * `mvp/StoryBoardDaemonTest` stays RED until then and its assertions are not duplicated here.
 *
 * Every rule lives in the store, so a client that bypasses the daemon still meets it.
 */
class StoryStatusPersistenceTest {

    private val json = Json { encodeDefaults = true }

    /** A fresh, not-yet-created stories file, the way the daemon finds one on a first boot. */
    private fun storiesFile(directory: File) = File(directory, "stories.json")

    private fun tempDir(): File = Files.createTempDirectory("infantry-stories-").toFile()

    /**
     * The durable half, started over [file] the way a daemon is started over a state directory. A
     * reopen is a NEW store over the same file -- the one thing a restart does to state -- and each
     * one mints its own ids, exactly as a real restart would.
     */
    private fun durable(file: File): StoryStore = JsonStoryStore(file)

    private fun StoryStore.create(
        projectId: String = "project-sprint-zero",
        title: String = "Run one story three ways",
    ) = save(StorySave(projectId = projectId, title = title))

    private fun StoryStore.moveTo(id: String, status: String) = move(StoryMove(id = id, status = status))

    /**
     * The exact wire keys of a story, so the corrected minimal shape cannot grow silently -- and so
     * nothing that was deliberately dropped (`description`, `position`, `createdAt`, `updatedAt`)
     * creeps back in across a restart either.
     */
    private fun assertMinimalWireKeys(story: Story) {
        assertEquals(
            setOf("id", "projectId", "title", "status"),
            json.encodeToJsonElement(Story.serializer(), story).jsonObject.keys,
            "the Story wire shape is the MVP minimum: id, projectId, title, status",
        )
    }

    /**
     * The vocabulary end to end, in the frozen order: `todo` -> `doing` -> `done`, every move
     * answered with the story in its new column, and every answer matching what `story_get` reads
     * back -- so a move is a status change and nothing more, in both directions.
     */
    @Test
    fun aStoryMovesThroughEveryFrozenColumn() {
        val store = durable(storiesFile(tempDir()))
        val story = store.create(title = "Ledger the tokens")

        val doing = store.moveTo(story.id, StoryStatus.DOING)
        assertEquals(StoryStatus.DOING, doing.status, "the answer to a move carries the new column")
        assertEquals(doing, store.get(story.id), "and the stored story is in that column too")

        val done = store.moveTo(story.id, StoryStatus.DONE)
        assertEquals(StoryStatus.DONE, done.status)
        assertEquals(done, store.get(story.id))

        val back = store.moveTo(story.id, StoryStatus.TODO)
        assertEquals(StoryStatus.TODO, back.status, "three columns, no partial order: done goes back")
        assertEquals(back, store.get(story.id))
        listOf(doing, done, back).forEach { assertMinimalWireKeys(it) }
    }

    /** A story may jump a column: the vocabulary says what the columns ARE, not what a walk is. */
    @Test
    fun aStoryMayMoveStraightToTheLastColumn() {
        val store = durable(storiesFile(tempDir()))
        val story = store.create()

        val done = store.moveTo(story.id, StoryStatus.DONE)

        assertEquals(StoryStatus.DONE, done.status)
        assertEquals(done, store.get(story.id))
    }

    /**
     * A move into the column the story is already in is not a change: the same story comes back, and
     * a restart finds the one column it was left in -- not a second write of the same fact.
     */
    @Test
    fun aMoveToTheColumnItIsAlreadyInChangesNothing() {
        val file = storiesFile(tempDir())
        val store = durable(file)
        val story = store.create()
        store.moveTo(story.id, StoryStatus.DOING)
        val before = file.readText()

        val again = store.moveTo(story.id, StoryStatus.DOING)

        assertEquals(story.copy(status = StoryStatus.DOING), again, "same column, same story")
        assertEquals(before, file.readText(), "staying in a column wrote nothing new")
    }

    /** A story's status is a full walk of the vocabulary, and a restart keeps each column it held. */
    @Test
    fun everyColumnAStoryCanHoldSurvivesAReopen() {
        val directory = tempDir()
        val file = storiesFile(directory)
        val original = durable(file)
        val ids = StoryStatus.ALL.associateWith { column ->
            original.create(title = "Hold $column").let { saved ->
                original.moveTo(saved.id, column).id
            }
        }

        val reopened = durable(file)

        StoryStatus.ALL.forEach { column ->
            val story = reopened.get(ids.getValue(column))
            assertEquals(column, story.status, "'$column' must survive the reopen")
            assertEquals("Hold $column", story.title)
        }
    }

    /**
     * The one this leaf exists for: a board as it was left -- several stories, in several projects,
     * in several columns -- comes back whole after the store is reopened, with nothing moved,
     * nothing reshaped and nothing lost.
     */
    @Test
    fun theBoardAsItWasLeftSurvivesAReopen() {
        val directory = tempDir()
        val file = storiesFile(directory)
        val store = durable(file)
        val first = store.create(projectId = "project-alpha", title = "Alpha work")
        val second = store.create(projectId = "project-beta", title = "Beta work")
        val third = store.create(projectId = "project-alpha", title = "More alpha work")
        store.moveTo(first.id, StoryStatus.DOING)
        store.moveTo(second.id, StoryStatus.DONE)

        val reopened = durable(file)

        assertEquals(store.get(first.id), reopened.get(first.id), "a doing story keeps its column")
        assertEquals(store.get(second.id), reopened.get(second.id), "a done story keeps its column")
        assertEquals(store.get(third.id), reopened.get(third.id), "an unmoved story stays in todo")
        assertEquals(StoryStatus.TODO, reopened.get(third.id).status)
        assertEquals("project-alpha", reopened.get(third.id).projectId, "its project survives too")
        reopened.get(third.id).let { assertMinimalWireKeys(it) }
    }

    /**
     * An edit survives as well: persistence is the whole story, not just its column -- but a null
     * field left the column alone, so the reopen reads the status the story was in, not the default.
     */
    @Test
    fun anEditedStoryKeepsItsColumnAcrossAReopen() {
        val file = storiesFile(tempDir())
        val store = durable(file)
        val story = store.create(title = "Ledger the tokens")
        store.moveTo(story.id, StoryStatus.DOING)

        store.save(StorySave(id = story.id, title = "Ledger the tokens, for real"))
        val reopened = durable(file)

        val after = reopened.get(story.id)
        assertEquals("Ledger the tokens, for real", after.title)
        assertEquals(StoryStatus.DOING, after.status, "the edit left the column where it was")
    }

    /**
     * A refused move writes nothing -- not to memory and not to the file -- so the reopen finds the
     * one column the story was really in. A refusal that persisted would be a stored lie about which
     * column the board was left showing.
     */
    @Test
    fun aRefusedMovePersistsNothing() {
        val file = storiesFile(tempDir())
        val store = durable(file)
        val story = store.create()
        store.moveTo(story.id, StoryStatus.DOING)
        val before = file.readText()

        assertFailsWith<IllegalArgumentException> { store.moveTo(story.id, "wontdo") }

        assertEquals(before, file.readText(), "a refused move wrote nothing")
        val reopened = durable(file)
        assertEquals(StoryStatus.DOING, reopened.get(story.id).status, "the story is where it was")
    }

    /** An unknown id never resurrects one across a restart: a reopen still refuses it. */
    @Test
    fun anUnknownIdIsStillRefusedAfterAReopen() {
        val file = storiesFile(tempDir())
        durable(file).create()

        val reopened = durable(file)

        assertFailsWith<IllegalArgumentException> { reopened.get("story-does-not-exist") }
        assertFailsWith<IllegalArgumentException> { reopened.moveTo("story-does-not-exist", "doing") }
        assertFailsWith<IllegalArgumentException> {
            reopened.save(StorySave(id = "story-does-not-exist", title = "Raced a delete"))
        }
    }

    /**
     * A corrupt or unreadable file is "no stories", never a daemon that will not start: the same
     * rule [com.superiorsoftllc.infantry.session.JsonSessionStore] holds sessions to. The store opens
     * and the stories simply are not there.
     */
    @Test
    fun aCorruptFileOpensAsAnEmptyStoreRatherThanFailing() {
        val file = storiesFile(tempDir())
        file.writeText("{ this is not json")

        val store = durable(file)

        assertFailsWith<IllegalArgumentException> { store.get("anything") }
        val created = store.create(title = "Started fresh")
        assertEquals(StoryStatus.TODO, created.status)
        assertEquals(created, durable(file).get(created.id), "and the fresh board persists")
    }

    /**
     * A status the frozen vocabulary does not spell never comes back OUT of a reopen: a file
     * carrying one is treated as corrupt exactly like a file that is not JSON, so no story ever
     * survives a restart wearing an unknown column.
     */
    @Test
    fun anOffVocabularyStatusNeverSurvivesAReopen() {
        val file = storiesFile(tempDir())
        file.writeText(
            """{"version":1,"stories":[{"id":"s1","projectId":"p","title":"T","status":"wontdo"}]}""",
        )

        val store = durable(file)

        assertFailsWith<IllegalArgumentException> {
            store.get("s1")
        }
        val created = store.create(title = "A real story")
        assertEquals(created, store.get(created.id))
        assertEquals(StoryStatus.TODO, created.status)
    }

    /**
     * A write is atomic: the file is never left truncated, so a reopen always sees a whole board.
     * Reading the file back as JSON rather than through the store is what makes this a statement
     * about the FILE and not about the code that wrote it.
     */
    @Test
    fun theFileIsAlwaysAWholeBoard() {
        val file = storiesFile(tempDir())
        val store = durable(file)
        val story = store.create()
        store.moveTo(story.id, StoryStatus.DOING)

        val stored = Json.parseToJsonElement(file.readText()).jsonObject
        val stories = stored["stories"]!!
        assertTrue(stories.jsonArray.isNotEmpty(), "the snapshot carries the board: $stored")
        assertEquals(1, stories.jsonArray.size)
        assertEquals(
            StoryStatus.DOING,
            stories.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content,
            "the file holds the column the story was left in",
        )
    }
}
