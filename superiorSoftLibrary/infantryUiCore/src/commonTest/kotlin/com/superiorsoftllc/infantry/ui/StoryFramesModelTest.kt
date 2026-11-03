package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.Story
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-STORY-1: the UI decodes the daemon's story frames (contract 1.8.0, the MVP story board) and
 * keeps them per project -- a `stories` frame replaces only the project(s) it names, and a single
 * `story` frame upserts by id (a `story_move` answer updates its status) -- and encodes the
 * `story_list` / `story_save` / `story_move` requests the daemon's router answers.
 */
class StoryFramesModelTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private fun stories(vararg x: Story) =
        Envelope(Envelope.STORIES, json.encodeToString(ListSerializer(Story.serializer()), x.toList()))

    private fun story(x: Story) = Envelope(Envelope.STORY, json.encodeToString(Story.serializer(), x))

    /**
     * (a) Each encoder produces the contract frame: type, and payload byte for byte. `story_list`
     * carries the raw project id, or empty data to list every project's board; `story_save` sends
     * projectId + title; `story_move` sends the id and the target column, omitting a null status.
     */
    @Test fun encodersProduceTheContractFrames() {
        val list = WireRequests.storyList("A")
        assertEquals(Envelope.STORY_LIST, list.type, "story_list names its frame")
        assertEquals("A", list.data, "story_list carries the raw project id as payload")

        val everyProject = WireRequests.storyList()
        assertEquals(Envelope.STORY_LIST, everyProject.type, "story_list still names its frame with no project")
        assertEquals("", everyProject.data, "story_list with no project carries empty data (every project's board)")

        val create = WireRequests.storySave("A", "Checkout")
        assertEquals(Envelope.STORY_SAVE, create.type, "story_save names its frame")
        assertEquals("""{"projectId":"A","title":"Checkout"}""", create.data, "a create sends projectId and title, omitting a null id")

        val createInEpic = WireRequests.storySave("A", "Checkout", "E1")
        assertEquals("""{"projectId":"A","title":"Checkout","epicId":"E1"}""", createInEpic.data, "an epicId is written after title when non-null (contract 1.22.0)")

        val move = WireRequests.storyMove("S1", "doing")
        assertEquals(Envelope.STORY_MOVE, move.type, "story_move names its frame")
        assertEquals("""{"id":"S1","status":"doing"}""", move.data, "a move sends the id and the target column")

        val stay = WireRequests.storyMove("S1")
        assertEquals("""{"id":"S1"}""", stay.data, "a null status is omitted (the story stays in its column)")
    }

    /**
     * (b) A `stories` frame is one project's slice: it replaces exactly the project it names and
     * keeps every other project's stories. Listing B after A keeps A's; a second A list replaces
     * only A's.
     */
    @Test fun aStoriesFrameReplacesOnlyItsProject() {
        val afterA = feed(stories(Story("S1", "A", "One", "todo"), Story("S2", "A", "Two", "doing")))
        assertEquals(listOf("S1", "S2"), afterA.stories["A"]?.map { it.id }, "precondition: A's stories are loaded in order")

        val afterB = feed(stories(Story("S3", "B", "One", "todo")), from = afterA)
        assertEquals(listOf("S1", "S2"), afterB.stories["A"]?.map { it.id }, "listing B keeps A's stories")
        assertEquals(listOf("S3"), afterB.stories["B"]?.map { it.id }, "B's stories are stored under B")

        val afterSecondA = feed(stories(Story("S4", "A", "Three", "done")), from = afterB)
        assertEquals(listOf("S4"), afterSecondA.stories["A"]?.map { it.id }, "a second A list replaces only A's stories")
        assertEquals(listOf("S3"), afterSecondA.stories["B"]?.map { it.id }, "B's stories survive A's replacement")
    }

    /** An empty `stories` frame names no project and clears the stories (mirrors the `epics` rule). */
    @Test fun anEmptyStoriesFrameClearsTheStories() {
        val loaded = feed(stories(Story("S1", "A", "One", "todo"), Story("S2", "B", "Two", "todo")))
        assertEquals(2, loaded.stories.values.sumOf { it.size }, "precondition: two stories loaded")

        val cleared = feed(stories(), from = loaded)
        assertEquals(0, cleared.stories.size, "an empty stories frame clears the stories")
    }

    /**
     * (c) A single `story` frame upserts by id inside its project: an unknown id is appended, a known
     * id is replaced in place (never duplicated) so a `story_move` answer updates its status, and the
     * other project's stories are untouched.
     */
    @Test fun aStoryFrameUpsertsById() {
        val loaded = feed(
            stories(Story("S1", "A", "One", "todo"), Story("S2", "A", "Two", "todo")),
            stories(Story("S9", "B", "Other", "todo")),
        )

        val added = feed(story(Story("S3", "A", "Three", "todo")), from = loaded)
        assertEquals(listOf("S1", "S2", "S3"), added.stories["A"]?.map { it.id }, "an unknown id is appended to its project")

        val moved = feed(story(Story("S1", "A", "One", "doing")), from = added)
        assertEquals(listOf("S1", "S2", "S3"), moved.stories["A"]?.map { it.id }, "a known id is replaced, not duplicated")
        assertEquals("doing", moved.stories["A"]?.first { it.id == "S1" }?.status, "a story_move answer updates its status")
        assertEquals(listOf("S9"), moved.stories["B"]?.map { it.id }, "the other project's stories are untouched")
    }
}
