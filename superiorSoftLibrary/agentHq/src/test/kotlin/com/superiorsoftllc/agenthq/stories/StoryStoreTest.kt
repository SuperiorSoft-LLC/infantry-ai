package com.superiorsoftllc.agenthq.stories

import com.superiorsoftllc.agenthq.protocol.Story
import com.superiorsoftllc.agenthq.protocol.StoryMove
import com.superiorsoftllc.agenthq.protocol.StorySave
import com.superiorsoftllc.agenthq.protocol.StoryStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MVP Sprint-Zero, slice B leaf B1 (ticket `eb034b36`, branch `feature-agenthq-mvp-board`) -- RED by design.
 *
 * Slice 0 (`b2fbb94d`) froze the seam this store serves and the corrected seam (`0822d8f`) then made a
 * story deliberately minimal: `{ id, projectId, title, status }` and nothing else. This suite pins the
 * two behaviours B1 owns -- creating a story in a project, and REFUSING a status the vocabulary does
 * not spell rather than quietly normalising it to the nearest column -- and it pins the minimal shape
 * itself, so a field cannot creep back in.
 *
 * Deliberately NOT here, so each behaviour keeps one seam: the full move sequence and restart
 * persistence are B2 (`20ce1d7c`); board listing is B3 (`4d4b74dc`); the daemon frames behind this
 * store (`story_save` / `story_get` / `story_move` / `story_list`) are wired by the integrating slice,
 * so `mvp/StoryBoardDaemonTest` stays RED until then and those assertions are not duplicated here.
 *
 * Every rule lives in the store, so a client that bypasses the daemon still meets it.
 */
class StoryStoreTest {

    private val json = Json { encodeDefaults = true }

    /** Counts the ids the store's factory hands out, so a refused create leaves no gap. */
    private var nextId = 1

    /** Ids `s1`, `s2`... in creation order. No clock: the corrected Story carries no timestamp. */
    private fun store(): StoryStore = InMemoryStoryStore(idFactory = { "s${nextId++}" })

    private fun StoryStore.create(
        projectId: String = "project-sprint-zero",
        title: String = "Run one story three ways",
    ) = save(StorySave(projectId = projectId, title = title))

    private fun StoryStore.moveTo(id: String, status: String) = move(StoryMove(id = id, status = status))

    /**
     * The exact wire keys of a story, so the corrected minimal shape cannot grow silently -- and so
     * nothing that was deliberately dropped (`description`, `position`, `createdAt`, `updatedAt`)
     * creeps back in.
     */
    private fun assertMinimalWireKeys(story: Story) {
        assertEquals(
            setOf("id", "projectId", "title", "status"),
            json.encodeToJsonElement(Story.serializer(), story).jsonObject.keys,
            "the Story wire shape is the MVP minimum: id, projectId, title, status",
        )
    }

    /**
     * A story created with `story_save` comes back whole: the project it belongs to, the title, a
     * fresh `todo` place, and the pinned wire keys.
     */
    @Test
    fun aStoryIsCreatedInAProject() {
        val store = store()

        val story = store.create(projectId = "project-sprint-zero", title = "Run one story three ways")

        assertTrue(story.id.isNotBlank(), "the store chose the id: '${story.id}'")
        assertEquals("project-sprint-zero", story.projectId)
        assertEquals(StoryStatus.TODO, story.status, "a new story starts in the first column")
        assertMinimalWireKeys(story)
        assertEquals(story, store.get(story.id), "what was stored is what comes back")
    }

    /** A create carries its own project: two projects never share a story. */
    @Test
    fun eachStoryKeepsTheProjectItWasCreatedIn() {
        val store = store()

        val alpha = store.create(projectId = "project-alpha", title = "Alpha work")
        val beta = store.create(projectId = "project-beta", title = "Beta work")

        assertEquals("project-alpha", alpha.projectId)
        assertEquals("project-beta", beta.projectId)
        assertEquals(alpha, store.get(alpha.id))
        assertEquals(beta, store.get(beta.id))
    }

    /**
     * A status the frozen vocabulary does not spell is REFUSED, never silently normalised to the
     * nearest column: the answer is an [IllegalArgumentException] naming what was asked for and the
     * whole vocabulary, and the story keeps its column.
     */
    @Test
    fun anUnknownStatusIsRefusedNotSilentlyNormalised() {
        val store = store()
        val story = store.create()

        val refusal = assertFailsWith<IllegalArgumentException> { store.moveTo(story.id, "wontdo") }

        assertTrue(
            refusal.message!!.contains("wontdo"),
            "the refusal names what was asked for: ${refusal.message}",
        )
        StoryStatus.ALL.forEach { column ->
            assertTrue(
                refusal.message!!.contains(column),
                "the refusal names the whole frozen vocabulary ($column): ${refusal.message}",
            )
        }
        // The story is exactly where it was: a refused move wrote nothing.
        assertEquals(StoryStatus.TODO, store.get(story.id).status, "a refused move never changed the column")
    }

    /**
     * Nothing gets normalised in either direction: a spelling that is ALMOST a column (whitespace, a
     * prefix, another board's column) is refused too, and no stored story ever carries a status the
     * vocabulary does not spell.
     */
    @Test
    fun anOffVocabularyStatusNeverReachesAStoredStory() {
        val store = store()
        val story = store.create()

        listOf("", "   ", "todo?", "dones", "in_progress", "closed", "todox").forEach { spelling ->
            assertFailsWith<IllegalArgumentException>("'$spelling' must not be normalised into a column") {
                store.moveTo(story.id, spelling)
            }
        }

        assertEquals(StoryStatus.TODO, store.get(story.id).status)
    }

    /**
     * The vocabulary is a gate, not a blanket refusal: a column the vocabulary does spell is accepted,
     * and the story lands in it.
     */
    @Test
    fun aStatusInsideTheFrozenVocabularyIsAccepted() {
        val store = store()
        val story = store.create()

        val moved = store.moveTo(story.id, StoryStatus.DOING)

        assertEquals(StoryStatus.DOING, moved.status)
        assertEquals(moved, store.get(story.id))
    }

    /** A create needs both halves of the join: a title and the project it belongs to. */
    @Test
    fun aCreateRefusesWhatItCannotPlace() {
        val store = store()

        assertFailsWith<IllegalArgumentException>("no title") { store.save(StorySave(projectId = "project-sprint-zero")) }
        assertFailsWith<IllegalArgumentException>("blank title") {
            store.save(StorySave(projectId = "project-sprint-zero", title = "   "))
        }
        assertFailsWith<IllegalArgumentException>("no project") { store.save(StorySave(title = "Unplaced work")) }
        assertFailsWith<IllegalArgumentException>("blank project") {
            store.save(StorySave(projectId = "  ", title = "Unplaced work"))
        }

        // Nothing was stored: the next create is still the first id, and only that one story exists.
        val story = store.create(title = "Placed work")
        assertEquals("s1", story.id, "a refused create left no trace in the id sequence")
    }

    /** The project's standing "refuse loudly" convention: an unknown id is never silently recreated. */
    @Test
    fun anUnknownIdIsRefused() {
        val store = store()

        assertFailsWith<IllegalArgumentException>("no story to get") { store.get("story-does-not-exist") }
        assertFailsWith<IllegalArgumentException>("no story to move") { store.moveTo("story-does-not-exist", "doing") }
        assertFailsWith<IllegalArgumentException>("no story to edit") {
            store.save(StorySave(id = "story-does-not-exist", title = "Raced a delete"))
        }
    }

    /** An edit leaves a null field as it is. */
    @Test
    fun anEditLeavesANullFieldAsItIs() {
        val store = store()
        val story = store.create(title = "Ledger the tokens")
        store.moveTo(story.id, StoryStatus.DOING)

        val edited = store.save(StorySave(id = story.id, title = "Ledger the tokens, for real"))

        assertEquals("Ledger the tokens, for real", edited.title)
        assertEquals(StoryStatus.DOING, edited.status, "a null status left the column alone")
        assertEquals(story.projectId, edited.projectId, "a null projectId left the project alone")
    }

    /**
     * A story carries no place of its own: the store refuses to invent one, and a move is a status
     * change and nothing more. Within-column order lives on
     * [com.superiorsoftllc.agenthq.protocol.Project.storyIds], owned by I0 -- this store must never
     * derive one from its own rows.
     */
    @Test
    fun aStoryCarriesNoPlaceOfItsOwn() {
        val store = store()
        val first = store.create(title = "One")
        val second = store.create(title = "Two")

        val moved = store.moveTo(first.id, StoryStatus.DOING)

        // No story gained, lost or shuffled a place: the field simply is not there.
        listOf(first, second, moved).forEach { assertMinimalWireKeys(it) }
        // And moving one story left the other exactly as it was.
        assertEquals(second, store.get(second.id), "moving another story is not this story's change")
        assertEquals(StoryStatus.TODO, store.get(second.id).status)
    }
}
